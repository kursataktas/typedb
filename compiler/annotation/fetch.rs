/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    sync::Arc,
};

use answer::{variable::Variable, Type};
use concept::type_::{
    attribute_type::AttributeType,
    constraint::{Constraint, ConstraintDescription},
    type_manager::TypeManager,
    Capability, OwnerAPI, TypeAPI,
};
use encoding::value::label::Label;
use ir::{
    pattern::ParameterID,
    pipeline::{
        fetch::{
            FetchListAttributeAsList, FetchListAttributeFromList, FetchListSubFetch, FetchObject, FetchSingleAttribute,
            FetchSome,
        },
        ParameterRegistry, VariableRegistry,
    },
    translation::TranslationContext,
};
use storage::snapshot::ReadableSnapshot;

use crate::annotation::{
    expression::compiled_expression::ExpressionValueType,
    function::{
        annotate_anonymous_function, AnnotatedFunction, AnnotatedUnindexedFunctions, IndexedAnnotatedFunctions,
    },
    pipeline::{annotate_stages_and_fetch, AnnotatedStage},
    AnnotationError,
};

#[derive(Debug, Clone)]
pub struct AnnotatedFetch {
    pub object: AnnotatedFetchObject,
}

#[derive(Debug, Clone)]
pub enum AnnotatedFetchSome {
    SingleVar(Variable),
    SingleAttribute(Variable, AttributeType<'static>),
    SingleFunction(AnnotatedFunction),

    Object(Box<AnnotatedFetchObject>),

    ListFunction(AnnotatedFunction),
    ListSubFetch(AnnotatedFetchListSubFetch),
    ListAttributesAsList(Variable, AttributeType<'static>),
    ListAttributesFromList(Variable, AttributeType<'static>),
}

#[derive(Debug, Clone)]
pub enum AnnotatedFetchObject {
    Entries(HashMap<ParameterID, AnnotatedFetchSome>),
    Attributes(Variable),
}

#[derive(Debug, Clone)]
pub struct AnnotatedFetchListSubFetch {
    pub variable_registry: VariableRegistry,
    pub input_variables: HashSet<Variable>,
    pub stages: Vec<AnnotatedStage>,
    pub fetch: AnnotatedFetch,
}

pub(crate) fn annotate_fetch(
    fetch: FetchObject,
    snapshot: &impl ReadableSnapshot,
    type_manager: &TypeManager,
    variable_registry: &VariableRegistry,
    parameters: &ParameterRegistry,
    indexed_annotated_functions: &IndexedAnnotatedFunctions,
    local_functions: Option<&AnnotatedUnindexedFunctions>,
    input_type_annotations: &BTreeMap<Variable, Arc<BTreeSet<Type>>>,
    input_value_type_annotations: &BTreeMap<Variable, ExpressionValueType>,
) -> Result<AnnotatedFetch, AnnotationError> {
    let object = annotate_object(
        fetch,
        snapshot,
        type_manager,
        variable_registry,
        parameters,
        indexed_annotated_functions,
        local_functions,
        input_type_annotations,
        input_value_type_annotations,
    )?;
    Ok(AnnotatedFetch { object })
}

fn annotate_object(
    object: FetchObject,
    snapshot: &impl ReadableSnapshot,
    type_manager: &TypeManager,
    variable_registry: &VariableRegistry,
    parameters: &ParameterRegistry,
    indexed_annotated_functions: &IndexedAnnotatedFunctions,
    local_functions: Option<&AnnotatedUnindexedFunctions>,
    input_type_annotations: &BTreeMap<Variable, Arc<BTreeSet<Type>>>,
    input_value_type_annotations: &BTreeMap<Variable, ExpressionValueType>,
) -> Result<AnnotatedFetchObject, AnnotationError> {
    match object {
        FetchObject::Entries(entries) => {
            let annotated_entries = annotated_object_entries(
                entries,
                snapshot,
                type_manager,
                variable_registry,
                parameters,
                indexed_annotated_functions,
                local_functions,
                input_type_annotations,
                input_value_type_annotations,
            )?;
            Ok(AnnotatedFetchObject::Entries(annotated_entries))
        }
        FetchObject::Attributes(attributes) => Ok(AnnotatedFetchObject::Attributes(attributes)),
    }
}

fn annotated_object_entries(
    entries: HashMap<ParameterID, FetchSome>,
    snapshot: &impl ReadableSnapshot,
    type_manager: &TypeManager,
    variable_registry: &VariableRegistry,
    parameters: &ParameterRegistry,
    indexed_annotated_functions: &IndexedAnnotatedFunctions,
    local_functions: Option<&AnnotatedUnindexedFunctions>,
    input_type_annotations: &BTreeMap<Variable, Arc<BTreeSet<Type>>>,
    input_value_type_annotations: &BTreeMap<Variable, ExpressionValueType>,
) -> Result<HashMap<ParameterID, AnnotatedFetchSome>, AnnotationError> {
    let mut annotated_entries = HashMap::new();
    for (key, value) in entries.into_iter() {
        let annotated_value = annotate_some(
            value,
            snapshot,
            type_manager,
            variable_registry,
            parameters,
            indexed_annotated_functions,
            local_functions,
            input_type_annotations,
            input_value_type_annotations,
        )
        .map_err(|err| AnnotationError::FetchEntry {
            key: parameters.fetch_key(key).unwrap().clone(),
            typedb_source: Box::new(err),
        })?;
        annotated_entries.insert(key, annotated_value);
    }
    Ok(annotated_entries)
}

fn annotate_some(
    some: FetchSome,
    snapshot: &impl ReadableSnapshot,
    type_manager: &TypeManager,
    variable_registry: &VariableRegistry,
    parameters: &ParameterRegistry,
    indexed_annotated_functions: &IndexedAnnotatedFunctions,
    local_functions: Option<&AnnotatedUnindexedFunctions>,
    input_type_annotations: &BTreeMap<Variable, Arc<BTreeSet<Type>>>,
    input_value_type_annotations: &BTreeMap<Variable, ExpressionValueType>,
) -> Result<AnnotatedFetchSome, AnnotationError> {
    match some {
        FetchSome::SingleVar(var) => Ok(AnnotatedFetchSome::SingleVar(var)),
        FetchSome::SingleAttribute(FetchSingleAttribute { variable, attribute }) => {
            let variable_name = variable_registry.get_variable_name(variable).unwrap();
            let attribute_type = type_manager
                .get_attribute_type(snapshot, &Label::build(&attribute))
                .map_err(|err| AnnotationError::ConceptRead { source: err })?
                .ok_or_else(|| AnnotationError::FetchAttributeNotFound {
                    var: variable_name.clone(),
                    name: attribute,
                })?;
            let owner_types = input_type_annotations.get(&variable).unwrap();
            validate_attribute_is_single(snapshot, type_manager, variable_name, owner_types, attribute_type.clone())?;
            Ok(AnnotatedFetchSome::SingleAttribute(variable, attribute_type))
        }
        FetchSome::SingleFunction(mut function) => {
            let annotated_function = annotate_anonymous_function(
                &mut function,
                snapshot,
                type_manager,
                indexed_annotated_functions,
                local_functions,
                input_type_annotations,
                input_value_type_annotations,
            )
            .map_err(|err| AnnotationError::FetchBlockFunctionInferenceError { typedb_source: err })?;
            Ok(AnnotatedFetchSome::SingleFunction(annotated_function))
        }
        FetchSome::Object(object) => {
            let object = annotate_object(
                *object,
                snapshot,
                type_manager,
                variable_registry,
                parameters,
                indexed_annotated_functions,
                local_functions,
                input_type_annotations,
                input_value_type_annotations,
            )?;
            Ok(AnnotatedFetchSome::Object(Box::new(object)))
        }
        FetchSome::ListFunction(mut function) => {
            let annotated_function = annotate_anonymous_function(
                &mut function,
                snapshot,
                type_manager,
                indexed_annotated_functions,
                local_functions,
                input_type_annotations,
                input_value_type_annotations,
            )
            .map_err(|err| AnnotationError::FetchBlockFunctionInferenceError { typedb_source: err })?;
            Ok(AnnotatedFetchSome::ListFunction(annotated_function))
        }
        FetchSome::ListSubFetch(sub_fetch) => {
            let annotated_sub_fetch = annotate_sub_fetch(
                snapshot,
                type_manager,
                indexed_annotated_functions,
                local_functions,
                sub_fetch,
                input_type_annotations,
                input_value_type_annotations,
            );
            Ok(AnnotatedFetchSome::ListSubFetch(annotated_sub_fetch?))
        }
        FetchSome::ListAttributesAsList(FetchListAttributeAsList { variable, attribute }) => {
            let variable_name = variable_registry.get_variable_name(variable).unwrap();
            let attribute_type = type_manager
                .get_attribute_type(snapshot, &Label::build(&attribute))
                .map_err(|err| AnnotationError::ConceptRead { source: err })?
                .ok_or_else(|| AnnotationError::FetchAttributeNotFound {
                    var: variable_name.clone(),
                    name: attribute,
                })?;
            for owner_type in input_type_annotations.get(&variable).unwrap().iter() {
                validate_attribute_is_streamable(
                    snapshot,
                    type_manager,
                    variable_name,
                    owner_type,
                    attribute_type.clone(),
                )?;
            }
            Ok(AnnotatedFetchSome::ListAttributesAsList(variable, attribute_type))
        }
        FetchSome::ListAttributesFromList(FetchListAttributeFromList { .. }) => {
            Err(AnnotationError::Unimplemented {
                description: "Fetching a list attribute is not yet supported.".to_owned(),
            })
            // // TODO: validate attribute type cardinality matches the syntax
            // Ok(AnnotatedFetchSome::ListAttributesFromList(fetch))
        }
    }
}

fn validate_attribute_is_single(
    snapshot: &impl ReadableSnapshot,
    type_manager: &TypeManager,
    owner: &str,
    owner_types: &BTreeSet<Type>,
    attribute_type: AttributeType<'static>,
) -> Result<(), AnnotationError> {
    for owner_type in owner_types {
        let owns = owner_type
            .as_object_type()
            .get_owns_attribute(snapshot, type_manager, attribute_type.clone())
            .map_err(|err| AnnotationError::ConceptRead { source: err })?
            .ok_or_else(|| AnnotationError::FetchSingleAttributeNotOwned {
                var: owner.to_owned(),
                owner: owner_type.get_label(snapshot, type_manager).unwrap().name().as_str().to_owned(),
                attribute: attribute_type.get_label(snapshot, type_manager).unwrap().name().as_str().to_owned(),
            })?;

        let max_card = owns
            .get_cardinality_constraints(snapshot, type_manager)
            .map_err(|err| AnnotationError::ConceptRead { source: err })?
            .iter()
            .filter_map(|card| {
                if let ConstraintDescription::Cardinality(card) = card.description() {
                    card.end()
                } else {
                    unreachable!()
                }
            })
            .max();
        if max_card.is_some_and(|max| max > 1) {
            return Err(AnnotationError::AttributeFetchCardTooHigh {
                var: owner.to_owned(),
                owner: owner_type.get_label(snapshot, type_manager).unwrap().name().as_str().to_owned(),
                attribute: attribute_type.get_label(snapshot, type_manager).unwrap().name().as_str().to_owned(),
            });
        }
    }
    Ok(())
}

fn validate_attribute_is_streamable(
    snapshot: &impl ReadableSnapshot,
    type_manager: &TypeManager,
    owner: &str,
    owner_type: &Type,
    attribute_type: AttributeType<'static>,
) -> Result<(), AnnotationError> {
    let _ = owner_type
        .as_object_type()
        .get_owns_attribute(snapshot, type_manager, attribute_type.clone())
        .map_err(|err| AnnotationError::ConceptRead { source: err })?
        .ok_or_else(|| AnnotationError::FetchAttributesNotOwned {
            var: owner.to_owned(),
            owner: owner_type.get_label(snapshot, type_manager).unwrap().name().as_str().to_owned(),
            attribute: attribute_type.get_label(snapshot, type_manager).unwrap().name().as_str().to_owned(),
        })?;
    Ok(())
}

fn annotate_sub_fetch(
    snapshot: &impl ReadableSnapshot,
    type_manager: &TypeManager,
    schema_function_annotations: &IndexedAnnotatedFunctions,
    annotated_preamble: Option<&AnnotatedUnindexedFunctions>,
    sub_fetch: FetchListSubFetch,
    input_type_annotations: &BTreeMap<Variable, Arc<BTreeSet<Type>>>,
    input_value_type_annotations: &BTreeMap<Variable, ExpressionValueType>,
) -> Result<AnnotatedFetchListSubFetch, AnnotationError> {
    let FetchListSubFetch { context, input_variables, stages, fetch } = sub_fetch;
    let TranslationContext { mut variable_registry, parameters, .. } = context;
    let (annotated_stages, annotated_fetch) = annotate_stages_and_fetch(
        snapshot,
        type_manager,
        schema_function_annotations,
        &mut variable_registry,
        &parameters,
        annotated_preamble,
        stages,
        Some(fetch),
        input_type_annotations.clone(),
        input_value_type_annotations.clone(),
    )?;
    Ok(AnnotatedFetchListSubFetch {
        variable_registry,
        input_variables,
        stages: annotated_stages,
        fetch: annotated_fetch.unwrap(),
    })
}
