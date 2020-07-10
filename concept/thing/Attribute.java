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

package grakn.concept.thing;

import grakn.concept.type.AttributeType;
import grakn.concept.type.ThingType;

import java.util.stream.Stream;

public interface Attribute extends Thing {

    /**
     * Case the {@code Concept} down to {@code Attribute}
     *
     * @return this {@code Attribute}
     */
    @Override
    default Attribute asAttribute() { return this; }

    /**
     * Get the immediate {@code AttributeType} in which this this {@code Attribute} is an instance of.
     *
     * @return the {@code AttributeType} of this {@code Attribute}
     */
    @Override
    AttributeType type();

    /**
     * Set an {@code Attribute} to be owned by this {@code Attribute}.
     *
     * @param attribute that will be owned by this {@code Attribute}
     * @return this {@code Attribute} for further manipulation
     */
    @Override
    Attribute has(Attribute attribute);

    /**
     * Get a stream of all {@code Thing} instances that own this {@code Attribute}.
     *
     * @return stream of all {@code Thing} instances that own this {@code Attribute}
     */
    Stream<? extends Thing> owners();

    Stream<? extends Thing> owners(ThingType ownerType);

    Attribute.Boolean asBoolean();

    Attribute.Long asLong();

    Attribute.Double asDouble();

    Attribute.String asString();

    Attribute.DateTime asDateTime();

    interface Boolean extends Attribute {

        java.lang.Boolean value();
    }

    interface Long extends Attribute {

        java.lang.Long value();
    }

    interface Double extends Attribute {

        java.lang.Double value();
    }

    interface String extends Attribute {

        java.lang.String value();
    }

    interface DateTime extends Attribute {

        java.time.LocalDateTime value();
    }
}
