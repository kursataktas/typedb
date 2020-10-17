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

package grakn.core.common.iterator;

import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class LoopIterator<T> implements ResourceIterator<T> {

    private final Predicate<T> predicate;
    private final UnaryOperator<T> function;
    private T next;
    private State state;

    private enum State {EMPTY, FETCHED, COMPLETED}

    LoopIterator(final T seed, final Predicate<T> predicate, final UnaryOperator<T> function) {
        state = State.FETCHED; // because first result is 'seed'
        this.next = seed;
        this.predicate = predicate;
        this.function = function;
    }

    private boolean fetchAndCheck() {
        next = function.apply(next);
        if (!predicate.test(next)) {
            state = State.COMPLETED;
            return false;
        }
        state = State.FETCHED;
        return true;
    }

    @Override
    public boolean hasNext() {
        switch (state) {
            case COMPLETED:
                return false;
            case FETCHED:
                return true;
            case EMPTY:
                return fetchAndCheck();
            default: // This should never be reached
                return false;
        }
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        state = State.EMPTY;
        return next;
    }

    @Override
    public void recycle() {}
}
