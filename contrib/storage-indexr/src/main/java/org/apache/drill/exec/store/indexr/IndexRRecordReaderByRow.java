/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.indexr;

import org.apache.commons.io.IOUtils;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.vector.BigIntVector;
import org.apache.drill.exec.vector.Float4Vector;
import org.apache.drill.exec.vector.Float8Vector;
import org.apache.drill.exec.vector.IntVector;
import org.apache.drill.exec.vector.VarCharVector;
import org.apache.spark.unsafe.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.indexr.data.BytePiece;
import io.indexr.segment.ColumnType;
import io.indexr.segment.Row;
import io.indexr.segment.Segment;
import io.indexr.segment.SegmentSchema;
import io.indexr.segment.helper.SegmentOpener;
import io.indexr.segment.helper.SingleWork;
import io.indexr.segment.pack.DataPack;
import io.indexr.util.MemoryUtil;

public class IndexRRecordReaderByRow extends IndexRRecordReader {
  private static final Logger log = LoggerFactory.getLogger(IndexRRecordReaderByPack.class);

  private SegmentOpener segmentOpener;
  private List<SingleWork> works;
  private int nextStepId = 0;
  private Iterator<Row> curIterator;
  private Segment curSegment;

  private Map<String, Segment> segmentMap;

  public IndexRRecordReaderByRow(String tableName,//
                                 SegmentSchema segmentSchema,//
                                 List<SchemaPath> projectColumns,//
                                 SegmentOpener segmentOpener,//
                                 List<SingleWork> works) {
    super(tableName, segmentSchema, projectColumns);
    this.segmentOpener = segmentOpener;
    this.works = works;
  }

  @Override
  public void setup(OperatorContext context, OutputMutator output) throws ExecutionSetupException {
    super.setup(context, output);
    this.segmentMap = new HashMap<>();
    try {
      for (SingleWork work : works) {
        if (!segmentMap.containsKey(work.segment())) {
          segmentMap.put(work.segment(), segmentOpener.open(work.segment()));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int next() {
    try {
      if (curIterator == null || !curIterator.hasNext()) {
        if (nextStepId >= works.size()) {
          return 0;
        }

        SingleWork stepWork = works.get(nextStepId);
        nextStepId++;
        curSegment = segmentMap.get(stepWork.segment());
        curIterator = curSegment.rowTraversal().iterator();
      }

      for (ProjectedColumnInfo info : projectedColumnInfos) {
        info.valueVector.setInitialCapacity(DataPack.MAX_COUNT);
      }

      int read = read(curSegment.schema(), curIterator, DataPack.MAX_COUNT);

      for (ProjectedColumnInfo info : projectedColumnInfos) {
        info.valueVector.getMutator().setValueCount(read);
      }

      return read;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private int read(SegmentSchema schema, Iterator<Row> iterator, int maxRow) throws Exception {
    int colCount = projectedColumnInfos.length;
    byte[] dataTypes = new byte[colCount];
    int[] columnIds = new int[colCount];
    for (int i = 0; i < colCount; i++) {
      ProjectedColumnInfo info = projectedColumnInfos[i];
      Integer columnId = DrillIndexRTable.mapColumn(info.columnSchema, schema);
      if (columnId == null) {
        throw new RuntimeException(String.format("column %s not found in %s", info.columnSchema, schema));
      }
      dataTypes[i] = info.columnSchema.dataType;
      columnIds[i] = columnId;
    }
    int rowId = 0;
    BytePiece bp = new BytePiece();
    ByteBuffer byteBuffer = MemoryUtil.getHollowDirectByteBuffer();
    while (rowId < maxRow && iterator.hasNext()) {
      Row row = iterator.next();
      for (int i = 0; i < colCount; i++) {
        ProjectedColumnInfo info = projectedColumnInfos[i];
        int columnId = columnIds[i];
        byte dataType = dataTypes[i];
        switch (dataTypes[i]) {
          case ColumnType.INT: {
            IntVector.Mutator mutator = (IntVector.Mutator) info.valueVector.getMutator();
            mutator.setSafe(rowId, row.getInt(columnId));
            break;
          }
          case ColumnType.LONG: {
            BigIntVector.Mutator mutator = (BigIntVector.Mutator) info.valueVector.getMutator();
            mutator.setSafe(rowId, row.getLong(columnId));
            break;
          }
          case ColumnType.FLOAT: {
            Float4Vector.Mutator mutator = (Float4Vector.Mutator) info.valueVector.getMutator();
            mutator.setSafe(rowId, row.getFloat(columnId));
            break;
          }
          case ColumnType.DOUBLE: {
            Float8Vector.Mutator mutator = (Float8Vector.Mutator) info.valueVector.getMutator();
            mutator.setSafe(rowId, row.getDouble(columnId));
            break;
          }
          case ColumnType.STRING: {
            VarCharVector.Mutator mutator = (VarCharVector.Mutator) info.valueVector.getMutator();
            row.getRaw(columnId, bp);
            if (bp.base != null) {
              assert bp.base instanceof byte[];

              byte[] arr = (byte[]) bp.base;
              mutator.setSafe(rowId, arr, (int) (bp.addr - Platform.BYTE_ARRAY_OFFSET), bp.len);
            } else {
              MemoryUtil.setByteBuffer(byteBuffer, bp.addr, bp.len, null);
              mutator.setSafe(rowId, byteBuffer, 0, byteBuffer.remaining());
            }
            break;
          }
          default:
            throw new IllegalStateException(String.format("Unhandled date type %s", info.columnSchema.dataType));
        }
      }
      rowId++;
    }
    return rowId;
  }

  @Override
  public void close() throws Exception {
    if (segmentMap != null) {
      segmentMap.values().forEach(IOUtils::closeQuietly);
      segmentMap = null;
      segmentOpener = null;
      works = null;
    }
  }
}