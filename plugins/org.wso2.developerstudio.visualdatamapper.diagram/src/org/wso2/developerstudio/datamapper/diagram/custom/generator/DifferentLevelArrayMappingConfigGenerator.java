/*
 * Copyright 2016 WSO2, Inc. (http://wso2.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.developerstudio.datamapper.diagram.custom.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.apache.commons.lang.StringUtils;
import org.wso2.developerstudio.datamapper.DataMapperOperatorType;
import org.wso2.developerstudio.datamapper.SchemaDataType;
import org.wso2.developerstudio.datamapper.diagram.Activator;
import org.wso2.developerstudio.datamapper.diagram.custom.configuration.operator.DMOperatorTransformerFactory;
import org.wso2.developerstudio.datamapper.diagram.custom.configuration.operator.transformers.DMOperatorTransformer;
import org.wso2.developerstudio.datamapper.diagram.custom.exception.DataMapperException;
import org.wso2.developerstudio.datamapper.diagram.custom.model.DMOperation;
import org.wso2.developerstudio.datamapper.diagram.custom.model.DMVariable;
import org.wso2.developerstudio.datamapper.diagram.custom.model.DMVariableType;
import org.wso2.developerstudio.datamapper.diagram.custom.model.DataMapperDiagramModel;
import org.wso2.developerstudio.datamapper.diagram.custom.model.transformers.TransformerConstants;
import org.wso2.developerstudio.datamapper.diagram.custom.util.ScriptGenerationUtil;
import org.wso2.developerstudio.eclipse.logging.core.IDeveloperStudioLog;
import org.wso2.developerstudio.eclipse.logging.core.Logger;

/**
 * This class extends abstract class {@link AbstractMappingConfigGenerator} and
 * generates mapping configuration from a {@link DataMapperDiagramModel} for
 * simple same level record and array type schema's
 *
 */
public class DifferentLevelArrayMappingConfigGenerator extends AbstractMappingConfigGenerator {

	private static final String ROOT_TAG = "root";
	private static final int FIRST_ELEMENT_INDEX = 0;

	private static final int VARIABLE_TYPE_INDEX = 0;
	private static IDeveloperStudioLog log = Logger.getLog(Activator.PLUGIN_ID);

	/**
	 * 
	 */
	private Map<String, Integer> forLoopBeanMap;

	/**
	 * forLoopBeanList contains the list of forLoopBeans in the generated script
	 */
	private List<ForLoopBean> forLoopBeanList;

	/**
	 * This is the root/parent bean and it will not be under a for loop in the
	 * generated script. Every operation and for loops should be under this
	 * rootBean
	 */
	private ForLoopBean rootBean;

	private Map<String, Integer> outputArrayVariableForLoopMap;
	private Map<String, Integer> outputObjectVariableForLoopMap;
	private Map<String, List<SchemaDataType>> variableMap;

	@Override
	public String generateMappingConfig(DataMapperDiagramModel model) throws DataMapperException {
		initializeAlgorithmFields();
		String globalVariables = instantiateGlobalVariables(model);
		List<MappingOperation> mappingOperationList = populateOperationListFromModel(model);
		String mainFunction = generateMainFunction(mappingOperationList, model);
		String customFunctions = generateCustomFunctions(model);
		return globalVariables + mainFunction + customFunctions;
	}

	private String instantiateGlobalVariables(DataMapperDiagramModel model) {
		StringBuilder functionBuilder = new StringBuilder();
		for (DMOperation operation : model.getOperationsList()) {
			if (DataMapperOperatorType.GLOBAL_VARIABLE.equals(operation.getOperatorType())) {
				functionBuilder.append("var " + operation.getProperty(TransformerConstants.GLOBAL_VARIABLE_NAME) + " = "
						+ operation.getProperty(TransformerConstants.GLOBAL_VARIABLE_DEFAULT_VALUE));
				functionBuilder.append(";\n");
			}
		}
		return functionBuilder.toString();
	}

	protected String generateCustomFunctions(DataMapperDiagramModel model) {
		StringBuilder functionBuilder = new StringBuilder();
		for (DMOperation operation : model.getOperationsList()) {
			if (DataMapperOperatorType.CUSTOM_FUNCTION.equals(operation.getOperatorType())) {
				functionBuilder.append(operation.getProperty(TransformerConstants.CUSTOM_FUNCTION_NAME) + " = "
						+ addFunctionDefinition(operation));
			}
			functionBuilder.append("\n");
		}
		return functionBuilder.toString();
	}

	protected String addFunctionDefinition(DMOperation operation) {
		StringBuilder functionBuilder = new StringBuilder();
		functionBuilder.append("function");
		String functionDefinition = (String) operation.getProperty(TransformerConstants.CUSTOM_FUNCTION_DEFINITION);
		functionBuilder.append(functionDefinition.substring(functionDefinition.indexOf("(")));
		return functionBuilder.toString();
	}

	private void initializeAlgorithmFields() {
		forLoopBeanMap = new HashMap<>();
		outputArrayVariableForLoopMap = new HashMap<>();
		outputObjectVariableForLoopMap = new HashMap<>();
		forLoopBeanList = new ArrayList<>();
		rootBean = new ForLoopBean(ROOT_TAG, ROOT_TAG);
		rootBean.setParentIndex(-1);
		getForLoopBeanList().add(rootBean);
		getForLoopBeanMap().put(ROOT_TAG, 0);
	}

	private String generateMainFunction(List<MappingOperation> mappingOperationList, DataMapperDiagramModel model)
			throws DataMapperException {
		String inRoot = model.getInputRootName();
		String outRoot = model.getOutputRootName();
		StringBuilder functionBuilder = new StringBuilder();
		String ouputVariableRootName = model.getVariablesArray().get(FIRST_ELEMENT_INDEX).getName();
		functionBuilder.append(getMainFunctionDefinition(inRoot, outRoot, ouputVariableRootName));
		functionBuilder.append(getJSCommandsForOperations(mappingOperationList, model));
		functionBuilder.append(getFunctionReturnString(ouputVariableRootName));
		return functionBuilder.toString();
	}

	@SuppressWarnings("unchecked")
	private String getJSCommandsForOperations(List<MappingOperation> mappingOperationList, DataMapperDiagramModel model)
			throws DataMapperException {
		variableMap = model.getVariableTypeMap();
		StringBuilder functionBuilder = new StringBuilder();
		ArrayList<MappingOperation> unassignedMappingOperations = new ArrayList<>();
		List<MappingOperation> mappingOperationListTemp = (List<MappingOperation>) ((ArrayList<MappingOperation>) mappingOperationList)
				.clone();
		mappingOperationListTemp = configureForLoopsWithMappingOperations(model, variableMap, functionBuilder,
				unassignedMappingOperations, mappingOperationListTemp);
		// All operations are now assign to ForLoopBean map. Transform
		// forLoopBean map for JS script.
		functionBuilder
				.append(transformForLoopBeansToJS(getRootBean(), mappingOperationList, model.getVariableTypeMap()));
		return functionBuilder.toString();
	}

	@SuppressWarnings("unchecked")
	private List<MappingOperation> configureForLoopsWithMappingOperations(DataMapperDiagramModel model,
			Map<String, List<SchemaDataType>> variableMap, StringBuilder functionBuilder,
			ArrayList<MappingOperation> unassignedMappingOperations, List<MappingOperation> mappingOperationListTemp)
			throws DataMapperException {
		int unassignedOperationCount;
		do {
			unassignedOperationCount = unassignedMappingOperations.size();
			if (unassignedOperationCount > 0) {
				mappingOperationListTemp = (List<MappingOperation>) unassignedMappingOperations.clone();
				unassignedMappingOperations.clear();
			}
			for (MappingOperation mappingOperation : mappingOperationListTemp) {
				List<DMVariable> inputVariables = mappingOperation.getInputVariables();
				List<DMVariable> outputVariables = mappingOperation.getOutputVariables();
				List<Integer> operationForLoopBeansList = new ArrayList<>();
				List<String> operationLastArrayElementsParentList = new ArrayList<>();
				Set<String> operationNullableVariableList = new HashSet<>();
				if (!inputVariables.isEmpty()) {
					for (DMVariable dmVariable : inputVariables) {
						if (dmVariable != null) {
							String mostChildVariableName = "";
							int mostChildVariableIndex = -1;
							// getting most child variable index
							if (DMVariableType.INTERMEDIATE.equals(dmVariable.getType())
									|| DMVariableType.OUTPUT.equals(dmVariable.getType())) {
								List<DMVariable> variableArray = model.getVariablesArray();
								mostChildVariableIndex = getMostChildAssociatedVariableIndex(
										model.getInputAdjList().get(dmVariable.getparentVariableOrOperationIndex()),
										variableArray);
								if (mostChildVariableIndex >= 0) {
									mostChildVariableName = variableArray.get(mostChildVariableIndex).getName();
								} else {
									mostChildVariableIndex = model.getInputVariablesArray().get(0);
									mostChildVariableName = model.getVariablesArray().get(mostChildVariableIndex)
											.getName();
								}
								dmVariable.setMostChildVariableIndex(mostChildVariableIndex);
							} else {
								mostChildVariableName = dmVariable.getName();
								mostChildVariableIndex = dmVariable.getIndex();
							}
							if (mostChildVariableIndex >= 0) {
								String[] variableNameArray = mostChildVariableName.split("\\.");
								String variableName = "";
								String parentVariableName = "";
								String parentArrayVariable = "";
								boolean firstIteration = true;
								Set<String> nullableVariableList = new HashSet<>();
								for (String nextName : variableNameArray) {
									if (!firstIteration) {
										// remove "." from variable name
										parentVariableName = variableName.substring(0, variableName.length() - 1);
									}
									variableName += nextName;
									if (variableMap.containsKey(variableName)) {
										SchemaDataType variableType = variableMap.get(variableName)
												.get(VARIABLE_TYPE_INDEX);
										if (SchemaDataType.ARRAY.equals(variableType)) {
											if (forLoopBeanNotExist(variableName)) {
												int indexOfForLoopBean = addForLoopInToMap(variableName,
														new ForLoopBean("i_" + nextName, variableName,
																nullableVariableList));
												nullableVariableList = new HashSet<>();
												addForLoopBeanIndexToParent(variableName, indexOfForLoopBean,
														parentArrayVariable);
												parentArrayVariable = variableName;
											} else {
												parentArrayVariable = variableName;
											}
										} else if (ScriptGenerationUtil.isVariableTypePrimitive(variableType)) {
											// leaf variable element
										} else if (SchemaDataType.OBJECT.equals(variableType)) {
											if (variableMap.get(variableName).contains(SchemaDataType.NULL)) {
												nullableVariableList.add(variableName);
											}
										} else {
											throw new DataMapperException(
													"Unsupported schemaDataType in WSO2 Data Mapper found : "
															+ variableType);
										}
									} else {
										throw new IllegalArgumentException(
												"Unknown variable name found : " + variableName);
									}
									variableName += ".";
									firstIteration = false;
								}
								operationNullableVariableList.addAll(nullableVariableList);
								// add most parent array for
								// operationForLoopBeansList
								if (StringUtils.isEmpty(parentArrayVariable)) {
									// root bean value
									operationForLoopBeansList.add(0);
								} else {
									operationForLoopBeansList.add(getForLoopBeanMap().get(parentArrayVariable));
								}
								// add parent Element of the variable to
								// operationElementsParentList
								// Only array elements need to be checked
								// whether
								// from the same parent or not
								operationLastArrayElementsParentList.add(parentArrayVariable);
							}
						}
					}
				}

				// validate the for loop beans of the variables. They should be
				// in
				// one branch.
				int indexOfMostInnerForLoopBean = -1;
				if (isValidOperationWithInputVariables(operationLastArrayElementsParentList)) {
					indexOfMostInnerForLoopBean = getMostInnerForLoopBeanFromList(operationForLoopBeansList);
				}
				String mostChildArrayOutputVariable = "";
				for (DMVariable outputVariable : outputVariables) {
					if (DMVariableType.INTERMEDIATE.equals(outputVariable.getType())
							&& !outputVariable.getName().contains("{")) {
						functionBuilder.append("var " + outputVariable.getName() + " = [];");
						functionBuilder.append("\n");
					} else if (DMVariableType.OUTPUT.equals(outputVariable.getType())) {
						mostChildArrayOutputVariable = updateOutputVariableForLoopMap(outputVariable, variableMap,
								indexOfMostInnerForLoopBean, operationForLoopBeansList, unassignedMappingOperations,
								mappingOperation);
					}
				}
				indexOfMostInnerForLoopBean = getMostInnerForLoopBeanFromList(operationForLoopBeansList);

				// find the most inner for loop bean to assign this operation
				if (indexOfMostInnerForLoopBean >= 0) {
					/*
					 * Instantiate operation of array type variables should go
					 * to previous object/array for loop bean
					 */
					if (DataMapperOperatorType.INSTANTIATE.equals(mappingOperation.getOperation().getOperatorType())
							&& SchemaDataType.ARRAY.equals(
									mappingOperation.getOperation().getProperty(TransformerConstants.VARIABLE_TYPE))) {
						String variableName = mappingOperation.getOutputVariables().get(0).getName();
						variableName = variableName.substring(0, variableName.lastIndexOf("."));
						if (outputArrayVariableForLoopMap.containsKey(variableName)) {
							indexOfMostInnerForLoopBean = outputArrayVariableForLoopMap.get(variableName);
						} else if (outputObjectVariableForLoopMap.containsKey(variableName)) {
							indexOfMostInnerForLoopBean = outputObjectVariableForLoopMap.get(variableName);
						} else {
							log.warn("Variable map doesn't contain variable : " + variableName);
						}
					}
					if (indexOfMostInnerForLoopBean < 0) {
						/*
						 * When one to array mapping occurs for loop index of
						 * array instantiate operation goes to -1. It also
						 * should be in root for loop.
						 */
						indexOfMostInnerForLoopBean = 0;
					}
					// Should update the output variable for loop bean mapping.
					// Most child for loop should be assigned
					if (!mostChildArrayOutputVariable.isEmpty()
							&& indexOfMostInnerForLoopBean > outputArrayVariableForLoopMap
									.get(mostChildArrayOutputVariable)) {
						outputArrayVariableForLoopMap.put(mostChildArrayOutputVariable, indexOfMostInnerForLoopBean);
					}
					getForLoopBeanList().get(indexOfMostInnerForLoopBean).getOperationList()
							.add(mappingOperation.getIndex());
				}
				mappingOperation.setOptionalElementList(operationNullableVariableList);
			}
			if (unassignedOperationCount == unassignedMappingOperations.size()) {
				assignUnresolvableOperationsToRoot(unassignedMappingOperations);
			}
		} while (!unassignedMappingOperations.isEmpty());
		return mappingOperationListTemp;
	}

	private void assignUnresolvableOperationsToRoot(List<MappingOperation> unassignedMappingOperations) {
		for (MappingOperation mappingOperation : unassignedMappingOperations) {
			getForLoopBeanList().get(0).getOperationList().add(mappingOperation.getIndex());
		}
		unassignedMappingOperations.clear();
	}

	private String updateOutputVariableForLoopMap(DMVariable outputVariable,
			Map<String, List<SchemaDataType>> variableMap, int indexOfMostInnerForLoopBean,
			List<Integer> operationForLoopBeansList, List<MappingOperation> unassignedMappingOperations,
			MappingOperation mappingOperation) throws DataMapperException {
		String outputVariableName = outputVariable.getName();
		String[] variableNameArray = outputVariableName.split("\\.");
		String variableName = "";
		String parentVariableName = "";
		String parentArrayVariable = "";
		String lastArrayVariable = "";
		boolean firstIteration = true;
		boolean operationAddedToUnassignedOperations = false;
		int previousForLoopIndex = 0;
		int previousObjectForLoopIndex = 0;
		for (String nextName : variableNameArray) {
			if (!firstIteration) {
				parentVariableName = variableName.substring(0, variableName.length() - 1);
			}
			variableName += nextName;
			if (variableMap.containsKey(variableName)) {
				SchemaDataType variableType = variableMap.get(variableName).get(VARIABLE_TYPE_INDEX);
				if (SchemaDataType.ARRAY.equals(variableType)) {
					if (!outputArrayVariableForLoopMap.containsKey(variableName)) {
						int targetForLoopIndex = getParentForLoopBeanIndex(previousForLoopIndex,
								indexOfMostInnerForLoopBean);
						if (targetForLoopIndex < 0) {
							unassignedMappingOperations.add(mappingOperation);
							operationAddedToUnassignedOperations = true;
							break;
						} else {
							outputArrayVariableForLoopMap.put(variableName, targetForLoopIndex);
							getForLoopBeanList().get(targetForLoopIndex).getArrayVariableListToInstantiate()
									.add(variableName);
							previousForLoopIndex = targetForLoopIndex;
						}
					} else {
						previousForLoopIndex = outputArrayVariableForLoopMap.get(variableName);
					}
					parentArrayVariable = variableName;
					lastArrayVariable = variableName;
				} else if (ScriptGenerationUtil.isVariableTypePrimitive(variableType)) {
					// leaf variable element
				} else if (SchemaDataType.OBJECT.equals(variableType)) {
					if (!outputObjectVariableForLoopMap.containsKey(variableName)) {
						int targetForLoopIndex = previousForLoopIndex;
						outputObjectVariableForLoopMap.put(variableName, targetForLoopIndex);
						getForLoopBeanList().get(targetForLoopIndex).getObjectVariableListToInstantiate()
								.add(variableName);
						previousObjectForLoopIndex = targetForLoopIndex;
					} else {
						previousObjectForLoopIndex = outputObjectVariableForLoopMap.get(variableName);
					}
				} else {
					throw new DataMapperException("Unsupported schemaDataType found : " + variableType);
				}
			} else {
				throw new IllegalArgumentException("Unknown variable name found : " + variableName);
			}
			variableName += ".";
			firstIteration = false;
		}
		if (!operationAddedToUnassignedOperations) {
			operationForLoopBeansList.add(previousForLoopIndex);
		}
		return lastArrayVariable;
	}

	/**
	 * Method for retrieve the first child for loop of targetRootForLoopIndex
	 * which is a parent ForLoopBean of mostChildForLoopBean.
	 * 
	 * @param targetRootForLoopIndex
	 * @param mostChildForLoopBean
	 * @return
	 */
	private int getParentForLoopBeanIndex(int targetRootForLoopIndex, int mostChildForLoopBean) {
		if (targetRootForLoopIndex == mostChildForLoopBean) {
			return mostChildForLoopBean;
		}
		if (mostChildForLoopBean >= 0) {
			ForLoopBean childForLoopBean = getForLoopBeanList().get(mostChildForLoopBean);
			int forLoopBeanIndex = mostChildForLoopBean;
			while (childForLoopBean.getParentIndex() != targetRootForLoopIndex) {
				forLoopBeanIndex = childForLoopBean.getParentIndex();
				if (forLoopBeanIndex == rootBean.getParentIndex()) {
					// Doesn't exist a for loop bean which is a child of
					// targetRootForLoop and a parent of mostChildForLoop
					return -1;
				}
				childForLoopBean = getForLoopBeanList().get(forLoopBeanIndex);
			}
			return forLoopBeanIndex;
		}
		return -1;
	}

	private int getMostChildAssociatedVariableIndex(ArrayList<Integer> inputVariableIndexList,
			List<DMVariable> variableList) {
		String mostChildVariableName = "";
		int mostChildVariableIndex = -1;
		for (Integer variableIndex : inputVariableIndexList) {
			DMVariable variable = variableList.get(variableIndex);
			String variableName = "";
			if (DMVariableType.INTERMEDIATE.equals(variable.getType())) {
				variableName = variableList.get(variable.getMostChildVariableIndex()).getName();
				if (mostChildVariableName.split("\\.").length < variableName.split("\\.").length) {
					mostChildVariableName = variableName;
					mostChildVariableIndex = variable.getMostChildVariableIndex();
				}
			} else {
				variableName = variableList.get(variableIndex).getName();
				if (mostChildVariableName.split("\\.").length < variableName.split("\\.").length) {
					mostChildVariableName = variableName;
					mostChildVariableIndex = variableIndex;
				}
			}
		}
		return mostChildVariableIndex;
	}

	@SuppressWarnings("unchecked")
	private String transformForLoopBeansToJS(ForLoopBean forLoopBean, List<MappingOperation> mappingOperationList,
			Map<String, List<SchemaDataType>> map) throws DataMapperException {
		StringBuilder functionBuilder = new StringBuilder();
		functionBuilder.append("\n");
		Stack<ForLoopBean> forLoopBeanParentStack = getParentForLoopBeanStack(forLoopBean);
		Stack<ForLoopBean> tempForLoopBeanParentStack = new Stack<ForLoopBean>();
		tempForLoopBeanParentStack = (Stack<ForLoopBean>) forLoopBeanParentStack.clone();
		boolean ifLoopCreated = false;
		if (!ROOT_TAG.equals(forLoopBean.getVariableName())) {
			// adding optional object element checks
			if (forLoopBean.getNullableVarialesList() != null && !forLoopBean.getNullableVarialesList().isEmpty()) {
				boolean firstElement = true;
				for (String optionalVariable : forLoopBean.getNullableVarialesList()) {
					if (!isOptionalVariableCheckedBefore(optionalVariable,
							getForLoopBeanList().get(forLoopBean.getParentIndex()))) {
						ifLoopCreated = true;
						if (!firstElement) {
							functionBuilder.append(" && ");
							firstElement = false;
						} else {
							functionBuilder.append("if( ");
						}
						functionBuilder.append("(" + ScriptGenerationUtil.getPrettyVariableNameInForOperation(
								new DMVariable(optionalVariable, "", DMVariableType.INPUT, SchemaDataType.ARRAY, -1),
								map, tempForLoopBeanParentStack, false, null, null) + ") ");
					}
				}
				if (ifLoopCreated) {
					functionBuilder.append("){");
					functionBuilder.append("\n");
				}
			}
			String forLoopVariableName = ScriptGenerationUtil.getPrettyVariableNameInForOperation(
					new DMVariable(forLoopBean.getVariableName(), "", DMVariableType.INPUT, SchemaDataType.ARRAY, -1),
					map, tempForLoopBeanParentStack, false, null, null);
			functionBuilder.append("for(" + forLoopBean.getIterativeName() + " in "
					+ forLoopVariableName.substring(0, forLoopVariableName.lastIndexOf("[")) + "){");
			functionBuilder.append("\n");
		} else {
			functionBuilder
					.append(ScriptGenerationUtil.instantiateForLoopCountVariables(forLoopBean, getForLoopBeanList()));
		}
		tempForLoopBeanParentStack = (Stack<ForLoopBean>) forLoopBeanParentStack.clone();
		// call operations and nested for loops
		List<Integer> operationsInForLoopList = forLoopBean.getOperationList();
		List<MappingOperation> forLoopBeanMappingOperations = new ArrayList<>();
		for (Integer index : operationsInForLoopList) {
			forLoopBeanMappingOperations.add(mappingOperationList.get(index));
		}
		forLoopBeanMappingOperations = sortMappingOperationList(forLoopBeanMappingOperations);
		for (MappingOperation mappingOperation : forLoopBeanMappingOperations) {
			DMVariable outputVariable = mappingOperation.getOutputVariables().get(0);
			int outputMappedForLoop = 0;
			String mostChildArrayElement;
/*			// skip instantiating empty objects
			if ((DataMapperOperatorType.INSTANTIATE.equals(mappingOperation.getOperation().getOperatorType())
					&& (SchemaDataType.OBJECT
							.equals(mappingOperation.getOperation().getProperty(TransformerConstants.VARIABLE_TYPE))
							|| SchemaDataType.ARRAY.equals(mappingOperation.getOperation()
									.getProperty(TransformerConstants.VARIABLE_TYPE))))) {
				if (outputObjectVariableForLoopMap.containsKey(mappingOperation.getOutputVariables().get(0).getName())
						|| outputArrayVariableForLoopMap
								.containsKey(mappingOperation.getOutputVariables().get(0).getName())) {
					continue;
				}
			}*/
			try {
				mostChildArrayElement = getMostChildArrayElementName(outputVariable.getName());
				if (!mostChildArrayElement.isEmpty()
						&& outputArrayVariableForLoopMap.containsKey(mostChildArrayElement)) {
					outputMappedForLoop = outputArrayVariableForLoopMap.get(mostChildArrayElement);
				}
				ForLoopBean outputMappedForLoopBean;
				if (outputMappedForLoop >= 0) {
					outputMappedForLoopBean = getForLoopBeanList().get(outputMappedForLoop);
					/*
					 * If this both output variable and mapping in the same for
					 * loop || array variable instantiate operation ||
					 * duplicating variable
					 */
					if ((outputMappedForLoop <= forLoopBean.getParentIndex())
							|| forLoopBean.equals(outputMappedForLoopBean)
							|| (DataMapperOperatorType.INSTANTIATE
									.equals(mappingOperation.getOperation().getOperatorType())
									&& SchemaDataType.ARRAY.equals(mappingOperation.getOperation()
											.getProperty(TransformerConstants.VARIABLE_TYPE)))) {
						functionBuilder.append(getJSCommandForOperation(mappingOperation, map, forLoopBean));
					} else {
						outputMappedForLoopBean.getOperationList().add(0,
								mappingOperationList.indexOf(mappingOperation));
					}
				} else {
					functionBuilder.append(getJSCommandForOperation(mappingOperation, map, forLoopBean));
				}
			} catch (DataMapperException e) {
				log.warn(e);
			}
		}
		List<Integer> nestedForLoopList = forLoopBean.getNestedForLoopList();
		for (Integer nestedForLoopIndex : nestedForLoopList) {
			functionBuilder.append(
					transformForLoopBeansToJS(getForLoopBeanList().get(nestedForLoopIndex), mappingOperationList, map));
		}

		if (!ROOT_TAG.equals(forLoopBean.getVariableName())) {
			// incrementing for loop iterate variable
			functionBuilder
					.append("\n" + ScriptGenerationUtil.getForLoopIterateName(forLoopBean, getForLoopBeanList(), true)
							+ "++;" + "\n");
			functionBuilder.append("}");
			functionBuilder.append("\n");
			if (ifLoopCreated) {
				functionBuilder.append("}");
				functionBuilder.append("\n");
			}
		}
		return functionBuilder.toString();
	}

	private String getMostChildArrayElementName(String fullVariableName) throws DataMapperException {
		String[] variableNameArray = fullVariableName.split("\\.");
		String variableName = "";
		String lastArrayVariable = "";
		for (String nextName : variableNameArray) {
			variableName += nextName;
			if (variableMap.containsKey(variableName)) {
				SchemaDataType variableType = variableMap.get(variableName).get(VARIABLE_TYPE_INDEX);
				if (SchemaDataType.ARRAY.equals(variableType)) {
					lastArrayVariable = variableName;
				}
			} else {
				throw new DataMapperException("Unknown variable name found : " + variableName);
			}
			variableName += ".";
		}
		return lastArrayVariable;
	}

	@SuppressWarnings("unchecked")
	private String getJSCommandForOperation(MappingOperation mappingOperation, Map<String, List<SchemaDataType>> map,
			ForLoopBean forLoopBean) throws DataMapperException {
		StringBuilder operationBuilder = new StringBuilder();
		List<DMVariable> outputVariables = mappingOperation.getOutputVariables();
		if (outputVariables.size() > 1) {
			operationBuilder.append("[ ");
		} else if (outputVariables.size() == 1) {
			if ((DataMapperOperatorType.PROPERTIES.equals(mappingOperation.getOperation().getOperatorType())
					|| DataMapperOperatorType.CONSTANT.equals(mappingOperation.getOperation().getOperatorType()))
					&& DMVariableType.INTERMEDIATE.equals(outputVariables.get(0).getType())) {
				return "";
			}
		}
		Stack<ForLoopBean> forLoopBeanParentStack = getParentForLoopBeanStack(forLoopBean);
		Stack<ForLoopBean> tempForLoopBeanParentStack = new Stack<ForLoopBean>();
		tempForLoopBeanParentStack = (Stack<ForLoopBean>) forLoopBeanParentStack.clone();
		boolean ifLoopCreated = false;
		if (mappingOperation.getOptionalElementList() != null && !mappingOperation.getOptionalElementList().isEmpty()) {
			boolean firstElement = true;
			for (String optionalVariable : mappingOperation.getOptionalElementList()) {
				if (!isOptionalVariableCheckedBefore(optionalVariable, forLoopBean)) {
					ifLoopCreated = true;
					if (!firstElement) {
						operationBuilder.append(" && ");
						firstElement = false;
					} else {
						operationBuilder.append("if( ");
					}
					operationBuilder.append("(" + ScriptGenerationUtil.getPrettyVariableNameInForOperation(
							new DMVariable(optionalVariable, "", DMVariableType.INPUT, SchemaDataType.ARRAY, -1), map,
							tempForLoopBeanParentStack, false, null, null) + ") ");
				}
			}
			if (ifLoopCreated) {
				operationBuilder.append("){");
				operationBuilder.append("\n");
			}
		}

		DMOperatorTransformer operatorTransformer = DMOperatorTransformerFactory
				.getDMOperatorTransformer(mappingOperation.getOperation().getOperatorType());

		operationBuilder.append(operatorTransformer.generateScriptForOperation(
				DifferentLevelArrayMappingConfigGenerator.class, mappingOperation.getInputVariables(),
				mappingOperation.getOutputVariables(), map, forLoopBeanParentStack, mappingOperation.getOperation(),
				getForLoopBeanList(), outputArrayVariableForLoopMap));
		operationBuilder.append("\n");
		if (ifLoopCreated) {
			operationBuilder.append("}");
			operationBuilder.append("\n");
		}
		return operationBuilder.toString();
	}

	private boolean isOptionalVariableCheckedBefore(String optionalVariable, ForLoopBean forLoopBean) {
		if (!"root".equals(forLoopBean.getVariableName())) {
			if (!forLoopBean.getNullableVarialesList().isEmpty()
					&& forLoopBean.getNullableVarialesList().contains(optionalVariable)) {
				return true;
			} else {
				if (forLoopBean.getParentIndex() > 0 && getForLoopBeanList().size() > forLoopBean.getParentIndex()) {
					return isOptionalVariableCheckedBefore(optionalVariable,
							getForLoopBeanList().get(forLoopBean.getParentIndex()));
				} else {
					return false;
				}
			}
		} else {
			return false;
		}
	}

	private Stack<ForLoopBean> getParentForLoopBeanStack(ForLoopBean forLoopBean) {
		Stack<ForLoopBean> parentForLoopStack = new Stack<>();
		if (forLoopBean.getParentIndex() < 0) {
			return parentForLoopStack;
		} else {
			parentForLoopStack = getParentForLoopBeanStack(getForLoopBeanList().get(forLoopBean.getParentIndex()));
			parentForLoopStack.push(forLoopBean);
			return parentForLoopStack;
		}
	}

	public ForLoopBean getRootBean() {
		return rootBean;
	}

	public void setRootBean(ForLoopBean rootBean) {
		this.rootBean = rootBean;
	}

	private boolean isValidOperationWithInputVariables(List<String> operationElementsParentList)
			throws DataMapperException {
		// parent variables should be in a same branch
		if (operationElementsParentList.isEmpty()) {
			return true;
		}
		String mostChildParentName = operationElementsParentList.get(0);
		for (int i = 1; i < operationElementsParentList.size(); i++) {
			String parentName = operationElementsParentList.get(i);
			if (mostChildParentName.length() > parentName.length()) {
				checkTwoParentsInTheSameBranch(mostChildParentName, parentName);
			} else {
				checkTwoParentsInTheSameBranch(parentName, mostChildParentName);
				mostChildParentName = parentName;
			}
		}
		return true;
	}

	private void checkTwoParentsInTheSameBranch(String mostChildParentName, String parentName)
			throws DataMapperException {
		if (!mostChildParentName.startsWith(parentName)) {
			throw new DataMapperException("Cannot use varibales in different types of objects to map."
					+ " Cannot find a unique identifier for the items mapped. Re-Evaluate your mappping.");
		}
	}

	private int getMostInnerForLoopBeanFromList(List<Integer> operationForLoopBeansList) {
		if (operationForLoopBeansList.isEmpty()) {
			return -1;
		}
		int mostChildForLoopIndex = operationForLoopBeansList.get(0);
		for (int i = 1; i < operationForLoopBeansList.size(); i++) {
			int thisForLoopBeanIndex = operationForLoopBeansList.get(i);
			if (getForLoopBeanList().get(mostChildForLoopIndex).getVariableName().length() < getForLoopBeanList()
					.get(thisForLoopBeanIndex).getVariableName().length()) {
				mostChildForLoopIndex = thisForLoopBeanIndex;
			}
		}
		return mostChildForLoopIndex;
	}

	private void addForLoopBeanIndexToParent(String variableName, int indexOfForLoopBean, String parentVariable) {
		if (!StringUtils.isEmpty(parentVariable)) {
			int parentIndex = getForLoopBeanMap().get(parentVariable);
			getForLoopBeanList().get(indexOfForLoopBean).setParentIndex(parentIndex);
			getForLoopBeanList().get(parentIndex).getNestedForLoopList().add(indexOfForLoopBean);
		} else {
			// root bean
			getForLoopBeanList().get(0).getNestedForLoopList().add(indexOfForLoopBean);
		}
	}

	private boolean forLoopBeanNotExist(String variableName) {
		return !getForLoopBeanMap().containsKey(variableName);
	}

	@Override
	public boolean validate(DataMapperDiagramModel model) {
		return true;
	}

	public Map<String, Integer> getForLoopBeanMap() {
		return forLoopBeanMap;
	}

	public void setForLoopBeanMap(Map<String, Integer> forLoopBeanMap) {
		this.forLoopBeanMap = forLoopBeanMap;
	}

	public List<ForLoopBean> getForLoopBeanList() {
		return forLoopBeanList;
	}

	public void setForLoopBeanList(List<ForLoopBean> forLoopBeanList) {
		this.forLoopBeanList = forLoopBeanList;
	}

	private int addForLoopInToMap(String variableName, ForLoopBean forLoopBean) {
		getForLoopBeanList().add(forLoopBean);
		int indexOfForLoopBean = getForLoopBeanList().size() - 1;
		forLoopBeanMap.put(variableName, indexOfForLoopBean);
		return indexOfForLoopBean;
	}

}
