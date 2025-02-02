/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

use std::{
    collections::HashMap,
    fmt::{Debug, Display, Formatter},
};

use encoding::graph::definition::definition_key::DefinitionKey;
use primitive::maybe_owns::MaybeOwns;

use crate::{
    pattern::variable_category::{VariableCategory, VariableOptionality},
    pipeline::FunctionReadError,
    translation::function::build_signature,
};

#[derive(Debug, Clone, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub enum FunctionID {
    Schema(DefinitionKey<'static>),
    Preamble(usize),
}

#[derive(Debug)]
pub struct FunctionSignature {
    pub(crate) function_id: FunctionID,
    pub(crate) arguments: Vec<VariableCategory>, // TODO: Arguments cannot be optional
    pub(crate) returns: Vec<(VariableCategory, VariableOptionality)>,
    pub(crate) return_is_stream: bool,
}

impl FunctionSignature {
    pub fn new(
        function_id: FunctionID,
        arguments: Vec<VariableCategory>,
        returns: Vec<(VariableCategory, VariableOptionality)>,
        return_is_stream: bool,
    ) -> FunctionSignature {
        Self { function_id, arguments, returns, return_is_stream }
    }

    pub fn function_id(&self) -> FunctionID {
        self.function_id.clone()
    }
}

impl FunctionID {
    pub fn as_definition_key(&self) -> Option<DefinitionKey<'static>> {
        if let FunctionID::Schema(definition_key) = self {
            Some(definition_key.clone())
        } else {
            None
        }
    }

    pub fn as_preamble(&self) -> Option<usize> {
        if let FunctionID::Preamble(index) = self {
            Some(*index)
        } else {
            None
        }
    }

    pub fn as_usize(&self) -> usize {
        match self {
            FunctionID::Schema(id) => id.definition_id().as_uint() as usize,
            FunctionID::Preamble(id) => *id,
        }
    }
}

impl Display for FunctionID {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            FunctionID::Schema(definition_key) => {
                write!(f, "SchemaFunction#{}", definition_key.definition_id().as_uint())
            }
            FunctionID::Preamble(index) => write!(f, "QueryFunction#{}", index),
        }
    }
}

pub trait FunctionIDAPI: Debug + Clone + Into<FunctionID> {}
impl FunctionIDAPI for DefinitionKey<'static> {}
impl FunctionIDAPI for usize {}

impl From<usize> for FunctionID {
    fn from(val: usize) -> Self {
        FunctionID::Preamble(val)
    }
}

impl From<DefinitionKey<'static>> for FunctionID {
    fn from(val: DefinitionKey<'static>) -> Self {
        FunctionID::Schema(val)
    }
}

pub trait FunctionSignatureIndex {
    fn get_function_signature(&self, name: &str)
        -> Result<Option<MaybeOwns<'_, FunctionSignature>>, FunctionReadError>;
}

#[derive(Debug)]
pub struct HashMapFunctionSignatureIndex {
    index: HashMap<String, FunctionSignature>,
}

impl HashMapFunctionSignatureIndex {
    pub fn build<'func>(buffered_typeql: impl Iterator<Item = (FunctionID, &'func typeql::Function)>) -> Self {
        let index = buffered_typeql
            .map(|(function_id, function)| {
                (function.signature.ident.as_str().to_owned(), build_signature(function_id, function))
            })
            .collect();
        Self { index }
    }

    pub fn empty() -> Self {
        Self::build([].into_iter())
    }

    pub fn into_map(self) -> HashMap<String, FunctionSignature> {
        self.index
    }
}

impl FunctionSignatureIndex for HashMapFunctionSignatureIndex {
    fn get_function_signature(
        &self,
        name: &str,
    ) -> Result<Option<MaybeOwns<'_, FunctionSignature>>, FunctionReadError> {
        if let Some(signature) = self.index.get(name) {
            Ok(Some(MaybeOwns::Borrowed(signature)))
        } else {
            Ok(None)
        }
    }
}
