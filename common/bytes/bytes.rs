/*
 * Copyright (C) 2023 Vaticle
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
 */

use std::fmt::{Display, Formatter, Pointer};
use crate::byte_array::ByteArray;

pub mod byte_array;

#[derive(Debug)]
pub enum ByteArrayOrRef<'bytes, const ARRAY_INLINE_SIZE: usize> {
    Array(ByteArray<ARRAY_INLINE_SIZE>),
    Reference(&'bytes [u8]),
}

impl<'bytes, const ARRAY_INLINE_SIZE: usize> ByteArrayOrRef<'bytes, ARRAY_INLINE_SIZE> {
    pub fn bytes(&'bytes self) -> &'bytes [u8] {
        match self {
            ByteArrayOrRef::Array(array) => array.bytes(),
            &ByteArrayOrRef::Reference(bytes) => bytes
        }
    }

    pub fn length(&self) -> usize {
        self.bytes().len()
    }

    pub fn truncate(self, length: usize) -> ByteArrayOrRef<'bytes, ARRAY_INLINE_SIZE> {
        assert!(length <= self.length());
        match self {
            ByteArrayOrRef::Array(mut array) => {
                array.truncate(length);
                ByteArrayOrRef::Array(array)

            },
            ByteArrayOrRef::Reference(bytes) => ByteArrayOrRef::Reference(
                &bytes[0..length]
            )
        }
    }

    pub fn unwrap_reference(self) -> &'bytes [u8] {
        if let ByteArrayOrRef::Reference(bytes) = self {
            bytes
        } else {
            panic!("{} cannot be unwrapped as a reference", self)
        }
    }
}

impl<'bytes,  const ARRAY_INLINE_SIZE: usize> Display for ByteArrayOrRef<'bytes, ARRAY_INLINE_SIZE> {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", dbg!(self))
    }
}
