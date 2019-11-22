/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.common.geo;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.ByteBufferStreamInput;
import org.elasticsearch.geometry.ShapeType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * A tree reader.
 *
 * This class supports checking bounding box
 * relations against the serialized geometry tree.
 */
public class GeometryTreeReader implements ShapeTreeReader {

    private final int extentOffset = 8;
    private final ByteBufferStreamInput input;
    private final CoordinateEncoder coordinateEncoder;

    public GeometryTreeReader(BytesRef bytesRef, CoordinateEncoder coordinateEncoder) {
        this.input = new ByteBufferStreamInput(ByteBuffer.wrap(bytesRef.bytes, bytesRef.offset, bytesRef.length));
        this.coordinateEncoder = coordinateEncoder;
    }

    public double getCentroidX() throws IOException {
        input.position(0);
        return coordinateEncoder.decodeX(input.readInt());
    }

    public double getCentroidY() throws IOException {
        input.position(4);
        return coordinateEncoder.decodeY(input.readInt());
    }

    @Override
    public Extent getExtent() throws IOException {
        input.position(extentOffset);
        Extent extent = input.readOptionalWriteable(Extent::new);
        if (extent != null) {
            return extent;
        }
        assert input.readVInt() == 1;
        ShapeType shapeType = input.readEnum(ShapeType.class);
        ShapeTreeReader reader = getReader(shapeType, input);
        return reader.getExtent();
    }

    @Override
    public GeoRelation relate(Extent extent) throws IOException {
        GeoRelation relation = GeoRelation.QUERY_DISJOINT;
        input.position(extentOffset);
        boolean hasExtent = input.readBoolean();
        if (hasExtent) {
            Optional<Boolean> extentCheck = EdgeTreeReader.checkExtent(new Extent(input), extent);
            if (extentCheck.isPresent()) {
                return extentCheck.get() ? GeoRelation.QUERY_INSIDE : GeoRelation.QUERY_DISJOINT;
            }
        }

        int numTrees = input.readVInt();
        for (int i = 0; i < numTrees; i++) {
            ShapeType shapeType = input.readEnum(ShapeType.class);
            ShapeTreeReader reader = getReader(shapeType, input);
            GeoRelation shapeRelation = reader.relate(extent);
            if (GeoRelation.QUERY_CROSSES == shapeRelation ||
                (GeoRelation.QUERY_DISJOINT == shapeRelation && GeoRelation.QUERY_INSIDE == relation)
            ) {
                return GeoRelation.QUERY_CROSSES;
            } else {
                relation = shapeRelation;
            }
        }

        return relation;
    }

    private static ShapeTreeReader getReader(ShapeType shapeType, ByteBufferStreamInput input) throws IOException {
        switch (shapeType) {
            case POLYGON:
                return new PolygonTreeReader(input);
            case POINT:
            case MULTIPOINT:
                return new Point2DReader(input);
            case LINESTRING:
            case MULTILINESTRING:
                return new EdgeTreeReader(input, false);
            default:
                throw new UnsupportedOperationException("unsupported shape type [" + shapeType + "]");
        }
    }
}
