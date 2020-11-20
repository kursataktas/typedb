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

package grakn.core.traversal.procedure;

import grakn.core.common.exception.GraknException;
import grakn.core.common.parameters.Label;
import grakn.core.graph.util.Encoding;
import grakn.core.traversal.Identifier;
import grakn.core.traversal.graph.TraversalVertex;
import graql.lang.common.GraqlToken;

import java.util.HashSet;
import java.util.Set;

import static grakn.common.util.Objects.className;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_CAST;
import static java.util.stream.Collectors.toSet;

abstract class ProcedureVertex<PROPERTIES extends TraversalVertex.Properties> extends TraversalVertex<ProcedureEdge, PROPERTIES> {

    private final Procedure procedure;
    private final boolean isStartingVertex;

    ProcedureVertex(Identifier identifier, Procedure procedure, boolean isStartingVertex) {
        super(identifier);
        this.procedure = procedure;
        this.isStartingVertex = isStartingVertex;
    }

    public ProcedureVertex.Thing asThing() {
        throw GraknException.of(ILLEGAL_CAST.message(className(this.getClass()), className(ProcedureVertex.Thing.class)));
    }

    public ProcedureVertex.Type asType() {
        throw GraknException.of(ILLEGAL_CAST.message(className(this.getClass()), className(ProcedureVertex.Type.class)));
    }

    static class Thing extends ProcedureVertex<Properties.Thing> {

        private Set<Filter.Thing> filters;

        Thing(Identifier identifier, Procedure procedure, boolean isStartingVertex) {
            super(identifier, procedure, isStartingVertex);
        }

        @Override
        protected Properties.Thing newProperties() {
            return new Properties.Thing();
        }

        @Override
        public void properties(Properties.Thing properties) {
            filters = Filter.Thing.of(properties, identifier());
            super.properties(properties);
        }

        @Override
        public boolean isThing() { return true; }

        @Override
        public ProcedureVertex.Thing asThing() { return this; }
    }

    static class Type extends ProcedureVertex<Properties.Type> {

        private Set<Filter.Type> filters;

        Type(Identifier identifier, Procedure procedure, boolean isStartingVertex) {
            super(identifier, procedure, isStartingVertex);
        }

        @Override
        protected Properties.Type newProperties() {
            return new Properties.Type();
        }

        @Override
        public void properties(Properties.Type properties) {
            filters = Filter.Type.of(properties);
            super.properties(properties);
        }

        @Override
        public boolean isType() { return true; }

        @Override
        public ProcedureVertex.Type asType() { return this; }
    }

    abstract static class Filter {

        @Override
        public abstract String toString();

        abstract static class Thing extends Filter {

            static Set<Filter.Thing> of(Properties.Thing property, Identifier identifier) {
                Set<Filter.Thing> filters = new HashSet<>();
                if (property.hasIID()) filters.add(new IID(identifier));
                else if (!property.types().isEmpty()) filters.add(new Types(property.types()));
                else if (!property.values().isEmpty()) filters.addAll(
                        property.values().stream().map(c -> new Value(c, identifier)).collect(toSet())
                );
                return filters;
            }

            static class IID extends Filter.Thing {

                private final Identifier param;

                IID(Identifier param) {
                    this.param = param;
                }

                @Override
                public String toString() {
                    return String.format("Filter: IID { iid: param(%s) }", param);
                }
            }

            static class Types extends Filter.Thing {

                private final Set<Label> labels;

                Types(Set<Label> labels) {
                    this.labels = labels;
                }

                @Override
                public String toString() {
                    return String.format("Filter: Types { labels: %s }", labels);
                }
            }

            static class Value extends Filter.Thing {

                private final GraqlToken.Comparator comparator;
                private final Identifier param;

                Value(GraqlToken.Comparator comparator, Identifier param) {
                    this.comparator = comparator;
                    this.param = param;
                }

                @Override
                public String toString() {
                    return String.format("Filter: Value { comparator: %s, value: param(%s) }", comparator, param);
                }
            }
        }

        static abstract class Type extends Filter {

            static Set<Filter.Type> of(Properties.Type properties) {
                Set<Filter.Type> filters = new HashSet<>();
                if (properties.label().isPresent()) filters.add(new Label(properties.label().get()));
                else if (properties.isAbstract()) filters.add(new Abstract());
                else if (properties.valueType().isPresent()) filters.add(new ValueType(properties.valueType().get()));
                else if (properties.regex().isPresent()) filters.add(new Regex(properties.regex().get()));
                return filters;
            }

            static class Label extends Filter.Type {

                private final grakn.core.common.parameters.Label label;

                Label(grakn.core.common.parameters.Label label) {
                    this.label = label;
                }

                @Override
                public String toString() {
                    return String.format("Filter: Label { label: %s }", label);
                }
            }

            static class Abstract extends Filter.Type {

                Abstract() {}

                @Override
                public String toString() {
                    return "Filter: Abstract { abstract: true }";
                }
            }

            static class ValueType extends Filter.Type {

                private final Encoding.ValueType valueType;

                ValueType(Encoding.ValueType valueType) {
                    this.valueType = valueType;
                }

                @Override
                public String toString() {
                    return String.format("Filter: Value Type { value: %s }", valueType);
                }
            }

            static class Regex extends Filter.Type {

                private final String regex;

                Regex(String regex) {
                    this.regex = regex;
                }

                @Override
                public String toString() {
                    return String.format("Filter: Regex { regex: %s }", regex);
                }
            }
        }
    }
}
