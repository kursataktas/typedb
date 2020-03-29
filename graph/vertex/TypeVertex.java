/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package hypergraph.graph.vertex;

import hypergraph.graph.Graph;
import hypergraph.graph.KeyGenerator;
import hypergraph.graph.Schema;
import hypergraph.graph.edge.Edge;
import hypergraph.graph.edge.TypeEdge;
import hypergraph.common.iterator.Iterators;

import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static hypergraph.common.collection.ByteArrays.join;

public abstract class TypeVertex extends Vertex<Schema.Vertex.Type, TypeVertex, Schema.Edge.Type, TypeEdge> {

    protected final Graph.Type graph;
    protected String label;
    protected Boolean isAbstract;
    protected Schema.DataType dataType;
    protected String regex;


    TypeVertex(Graph.Type graph, Schema.Vertex.Type type, byte[] iid, String label) {
        super(iid, type);
        this.graph = graph;
        this.label = label;
    }

    public static byte[] generateIID(KeyGenerator keyGenerator, Schema.Vertex.Type schema) {
        return join(schema.prefix().key(), keyGenerator.forType(schema.root()));
    }

    public static byte[] generateIndex(String label) {
        return join(Schema.Index.TYPE.prefix().key(), label.getBytes());
    }

    public String label() {
        return label;
    }

    public abstract boolean isAbstract();

    public abstract TypeVertex setAbstract(boolean isAbstract);

    public abstract Schema.DataType dataType();

    public abstract TypeVertex dataType(Schema.DataType dataType);

    public abstract String regex();

    public abstract TypeVertex regex(String regex);

    public abstract class DirectedTypeEdges extends DirectedEdges<TypeVertex, Schema.Edge.Type, TypeEdge> {

        DirectedTypeEdges(Direction direction) {
            super(direction);
        }

        @Override
        public void add(Schema.Edge.Type schema, TypeVertex adjacent) {
            TypeVertex from = direction.isOut() ? TypeVertex.this : adjacent;
            TypeVertex to = direction.isOut() ? adjacent : TypeVertex.this;
            TypeEdge edge = new TypeEdge.Buffered(graph, schema, from, to);
            edges.computeIfAbsent(edge.schema(), e -> ConcurrentHashMap.newKeySet()).add(edge);
            to.ins().addNonRecursive(edge);
        }

        @Override
        public void removeNonRecursive(TypeEdge edge) {
            if (edges.containsKey(edge.schema())) edges.get(edge.schema()).remove(edge);
        }
    }

    public static class Buffered extends TypeVertex {

        public Buffered(Graph.Type graph, Schema.Vertex.Type schema, byte[] iid, String label) {
            super(graph, schema, iid, label);
        }

        @Override
        protected DirectedTypeEdges newDirectedEdges(DirectedEdges.Direction direction) {
            return new BufferedDirectedTypeEdges(direction);
        }

        public Schema.Status status() {
            return Schema.Status.BUFFERED;
        }

        public boolean isAbstract() {
            return isAbstract;
        }

        public TypeVertex setAbstract(boolean isAbstract) {
            this.isAbstract = isAbstract;
            return this;
        }

        public Schema.DataType dataType() {
            return dataType;
        }

        public TypeVertex dataType(Schema.DataType dataType) {
            this.dataType = dataType;
            return this;
        }

        public String regex() {
            return regex;
        }

        public TypeVertex regex(String regex) {
            this.regex = regex;
            return this;
        }

        @Override
        public void commit() {
            graph.storage().put(iid);
            commitIndex();
            commitProperties();
            commitEdges();
        }

        void commitIndex() {
            graph.storage().put(join(Schema.Index.TYPE.prefix().key(), label.getBytes()), iid);
        }

        void commitProperties() {
            commitPropertyLabel();
            if (isAbstract != null && !isAbstract) commitPropertyAbstract();
            if (dataType != null) commitPropertyDataType();
            if (regex != null && !regex.isEmpty()) commitPropertyRegex();
        }

        void commitPropertyAbstract() {
            graph.storage().put(join(iid, Schema.Property.ABSTRACT.infix().key()));
        }

        void commitPropertyLabel() {
            graph.storage().put(join(iid, Schema.Property.LABEL.infix().key()), label.getBytes());
        }

        void commitPropertyDataType() {
            graph.storage().put(join(iid, Schema.Property.DATATYPE.infix().key()), dataType.value());
        }

        void commitPropertyRegex() {
            graph.storage().put(join(iid, Schema.Property.REGEX.infix().key()), regex.getBytes());
        }

        void commitEdges() {
            outs.forEach(Edge::commit);
            ins.forEach(Edge::commit);
        }

        public class BufferedDirectedTypeEdges extends DirectedTypeEdges {

            BufferedDirectedTypeEdges(Direction direction) {
                super(direction);
            }

            @Override
            public Iterator<TypeVertex> get(Schema.Edge.Type schema) {
                if (edges.get(schema) != null) return Iterators.apply(edges.get(schema).iterator(), Edge::to);
                return Collections.emptyIterator();
            }

            @Override
            public void remove(Schema.Edge.Type schema, TypeVertex adjacent) {
                if (edges.containsKey(schema)) {
                    edges.get(schema).parallelStream().filter(e -> e.to().equals(adjacent)).forEach(Edge::delete);
                }
            }

            @Override
            public void remove(Schema.Edge.Type schema) {
                if (edges.containsKey(schema)) edges.get(schema).parallelStream().forEach(Edge::delete);
            }
        }
    }
    public static class Persisted extends TypeVertex {


        public Persisted(Graph.Type graph, byte[] iid, String label) {
            super(graph, Schema.Vertex.Type.of(iid[0]), iid, label);
        }

        public Persisted(Graph.Type graph, byte[] iid) {
            super(graph, Schema.Vertex.Type.of(iid[0]), iid,
                  new String(graph.storage().get(join(iid, Schema.Property.LABEL.infix().key()))));
        }

        @Override
        protected DirectedEdges<TypeVertex, Schema.Edge.Type, TypeEdge> newDirectedEdges(DirectedEdges.Direction direction) {
            return new PersistedDirectedTypeEdges(direction);
        }

        @Override
        public Schema.Status status() {
            return Schema.Status.PERSISTED;
        }

        @Override
        public boolean isAbstract() {
            if (isAbstract != null) return isAbstract;
            byte[] abs = graph.storage().get(join(iid, Schema.Property.ABSTRACT.infix().key()));
            isAbstract = abs != null;
            return isAbstract;
        }

        @Override
        public TypeVertex setAbstract(boolean isAbstract) {
            return null; // TODO
        }

        @Override
        public Schema.DataType dataType() {
            if (dataType != null) return dataType;
            byte[] val = graph.storage().get(join(iid, Schema.Property.DATATYPE.infix().key()));
            if (val != null) dataType = Schema.DataType.of(val[0]);
            return dataType;
        }

        @Override
        public TypeVertex dataType(Schema.DataType dataType) {
            return null; // TODO
        }

        @Override
        public String regex() {
            if (regex != null) return regex;
            byte[] val = graph.storage().get(join(iid, Schema.Property.REGEX.infix().key()));
            if (val != null) regex = new String(val);
            return regex;
        }

        @Override
        public TypeVertex regex(String regex) {
            return null; // TODO
        }

        @Override
        public void commit() {
            commitEdges();
        }

        private void commitEdges() {
            outs.forEach(Edge::commit);
            ins.forEach(Edge::commit);
        }

        public class PersistedDirectedTypeEdges extends DirectedTypeEdges {

            PersistedDirectedTypeEdges(Direction direction) {
                super(direction);
            }

            @Override
            public Iterator<TypeVertex> get(Schema.Edge.Type schema) {
                Schema.Infix edgeInfix = direction.isOut() ? schema.out() : schema.in();
                Function<TypeEdge, TypeVertex> vertexFn = direction.isOut() ? Edge::to : Edge::from;

                Iterator<TypeEdge> storageIterator = graph.storage().iterate(
                        join(iid, edgeInfix.key()),
                        (iid, value) -> new TypeEdge.Persisted(graph, iid)
                );

                if (edges.get(schema) != null) return Iterators.link(edges.get(schema).iterator(), storageIterator).apply(vertexFn);
                else return Iterators.apply(storageIterator, vertexFn);
            }

            @Override
            public void remove(Schema.Edge.Type schema, TypeVertex adjacent) {
                Optional<TypeEdge> container;
                if (edges.containsKey(schema) && (container = edges.get(schema).stream().filter(e -> e.to().equals(adjacent)).findAny()).isPresent()) {
                    edges.get(schema).remove(container.get());
                } else {
                    Schema.Infix infix = direction.isOut() ? schema.out() : schema.in();
                    byte[] edgeIID = join(iid, infix.key(), adjacent.iid);
                    if (graph.storage().get(edgeIID) != null) {
                        (new TypeEdge.Persisted(graph, edgeIID)).delete();
                    }
                }
            }

            @Override
            public void remove(Schema.Edge.Type schema) {
                if (edges.containsKey(schema)) edges.get(schema).parallelStream().forEach(Edge::delete);
                Iterator<TypeEdge> storageIterator = graph.storage().iterate(
                        join(iid, direction.isOut() ? schema.out().key() : schema.in().key()),
                        (iid, value) -> new TypeEdge.Persisted(graph, iid)
                );
                storageIterator.forEachRemaining(Edge::delete);
            }
        }
    }
}
