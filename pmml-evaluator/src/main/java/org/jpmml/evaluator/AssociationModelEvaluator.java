/*
 * Copyright (c) 2013 Villu Ruusmann
 *
 * This file is part of JPMML-Evaluator
 *
 * JPMML-Evaluator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Evaluator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Evaluator.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.evaluator;

import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.dmg.pmml.AssociationModel;
import org.dmg.pmml.AssociationRule;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.Item;
import org.dmg.pmml.ItemRef;
import org.dmg.pmml.Itemset;
import org.dmg.pmml.MiningFunctionType;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Target;
import org.jpmml.manager.InvalidFeatureException;
import org.jpmml.manager.UnsupportedFeatureException;

public class AssociationModelEvaluator extends ModelEvaluator<AssociationModel> implements HasEntityRegistry<AssociationRule> {

	public AssociationModelEvaluator(PMML pmml){
		this(pmml, find(pmml.getModels(), AssociationModel.class));
	}

	public AssociationModelEvaluator(PMML pmml, AssociationModel associationModel){
		super(pmml, associationModel);
	}

	@Override
	public String getSummary(){
		return "Association rules";
	}

	/**
	 * @return <code>null</code> Always.
	 */
	@Override
	public Target getTarget(FieldName name){
		return null;
	}

	@Override
	public BiMap<String, AssociationRule> getEntityRegistry(){
		return getValue(AssociationModelEvaluator.entityCache);
	}

	@Override
	public void verify(){
		List<FieldName> targetFields = getTargetFields();

		if(targetFields.size() > 0){
			throw new InvalidFeatureException("Too many target fields", getMiningSchema());
		}

		super.verify();
	}

	@Override
	public Map<FieldName, ?> evaluate(ModelEvaluationContext context){
		AssociationModel associationModel = getModel();
		if(!associationModel.isScorable()){
			throw new InvalidResultException(associationModel);
		}

		Map<FieldName, Association> predictions;

		MiningFunctionType miningFunction = associationModel.getFunctionName();
		switch(miningFunction){
			case ASSOCIATION_RULES:
				predictions = evaluateAssociationRules(context);
				break;
			default:
				throw new UnsupportedFeatureException(associationModel, miningFunction);
		}

		return OutputUtil.evaluate(predictions, context);
	}

	private Map<FieldName, Association> evaluateAssociationRules(EvaluationContext context){
		AssociationModel associationModel = getModel();

		Collection<?> activeValue = getActiveValue(context);

		Set<String> input = createInput(activeValue, context);

		Map<String, Boolean> flags = Maps.newLinkedHashMap();

		List<Itemset> itemsets = associationModel.getItemsets();
		for(Itemset itemset : itemsets){
			flags.put(itemset.getId(), isSubset(input, itemset));
		}

		List<AssociationRule> associationRules = associationModel.getAssociationRules();

		BitSet antecedentFlags = new BitSet(associationRules.size());
		BitSet consequentFlags = new BitSet(associationRules.size());

		for(int i = 0; i < associationRules.size(); i++){
			AssociationRule associationRule = associationRules.get(i);

			Boolean antecedentFlag = flags.get(associationRule.getAntecedent());
			if(antecedentFlag == null){
				throw new InvalidFeatureException(associationRule);
			}

			antecedentFlags.set(i, antecedentFlag);

			Boolean consequentFlag = flags.get(associationRule.getConsequent());
			if(consequentFlag == null){
				throw new InvalidFeatureException(associationRule);
			}

			consequentFlags.set(i, consequentFlag);
		}

		Association association = new Association(associationRules, antecedentFlags, consequentFlags){

			@Override
			public BiMap<String, Item> getItemRegistry(){
				return AssociationModelEvaluator.this.getItemRegistry();
			}

			@Override
			public BiMap<String, Itemset> getItemsetRegistry(){
				return AssociationModelEvaluator.this.getItemsetRegistry();
			}

			@Override
			public BiMap<String, AssociationRule> getAssociationRuleRegistry(){
				return AssociationModelEvaluator.this.getEntityRegistry();
			}
		};

		return Collections.singletonMap(getTargetField(), association);
	}

	public Collection<?> getActiveValue(EvaluationContext context){
		List<FieldName> activeFields = getActiveFields();
		List<FieldName> groupFields = getGroupFields();

		// Custom IBM SPSS-style model: no group fields, one or more active fields
		if(groupFields.size() == 0){

			if(activeFields.size() < 0){
				throw new InvalidFeatureException("No active fields", getMiningSchema());
			}

			List<String> result = Lists.newArrayList();

			for(FieldName activeField : activeFields){
				FieldValue value = context.getField(activeField);

				if(value == null){
					context.addWarning("Missing argument \"" + activeField.getValue() + "\"");

					continue;
				} // End if

				if(value.equalsString("T")){
					result.add(activeField.getValue());
				} else

				if(value.equalsString("F")){
					continue;
				} else

				{
					throw new EvaluationException();
				}
			}

			return result;
		} else

		// Standard model: one group field, one active field
		if(groupFields.size() == 1){

			if(activeFields.size() < 1){
				throw new InvalidFeatureException("No active fields", getMiningSchema());
			} else

			if(activeFields.size() > 1){
				throw new InvalidFeatureException("Too many active fields", getMiningSchema());
			}

			FieldName activeField = activeFields.get(0);

			FieldValue value = context.getField(activeField);
			if(value == null){
				throw new MissingFieldException(activeField);
			}

			Collection<?> result;

			try {
				result = (Collection<?>)FieldValueUtil.getValue(value);
			} catch(ClassCastException cce){
				throw new TypeCheckException(Collection.class, value);
			}

			return result;
		} else

		{
			throw new InvalidFeatureException(getMiningSchema());
		}
	}

	/**
	 * @return A set of {@link Item#getId() Item identifiers}.
	 */
	private Set<String> createInput(Collection<?> values, EvaluationContext context){
		Set<String> result = Sets.newLinkedHashSet();

		Map<String, String> valueItems = (getItemValues().inverse());

		values:
		for(Object value : values){
			String stringValue = TypeUtil.format(value);

			String id = valueItems.get(stringValue);
			if(id == null){
				context.addWarning("Unknown item value \"" + stringValue + "\"");

				continue values;
			}

			result.add(id);
		}

		return result;
	}

	static
	private boolean isSubset(Set<String> input, Itemset itemset){
		boolean result = true;

		List<ItemRef> itemRefs = itemset.getItemRefs();
		for(ItemRef itemRef : itemRefs){
			result &= input.contains(itemRef.getItemRef());

			if(!result){
				return false;
			}
		}

		return result;
	}

	/**
	 * @return A bidirectional map between {@link Item#getId Item identifiers} and {@link Item instances}.
	 */
	private BiMap<String, Item> getItemRegistry(){
		return getValue(AssociationModelEvaluator.itemCache);
	}

	/**
	 * @return A bidirectional map between {@link Itemset#getId() Itemset identifiers} and {@link Itemset instances}.
	 */
	private BiMap<String, Itemset> getItemsetRegistry(){
		return getValue(AssociationModelEvaluator.itemsetCache);
	}

	/**
	 * @return A bidirectional map between {@link Item#getId() Item identifiers} and {@link Item#getValue() Item values}.
	 */
	private BiMap<String, String> getItemValues(){
		return getValue(AssociationModelEvaluator.itemValueCache);
	}

	static
	private BiMap<String, String> parseItemValues(AssociationModel associationModel){
		BiMap<String, String> result = HashBiMap.create();

		List<Item> items = associationModel.getItems();
		for(Item item : items){
			result.put(item.getId(), item.getValue());
		}

		return result;
	}

	private static final LoadingCache<AssociationModel, BiMap<String, AssociationRule>> entityCache = CacheBuilder.newBuilder()
		.weakKeys()
		.build(new CacheLoader<AssociationModel, BiMap<String, AssociationRule>>(){

			@Override
			public BiMap<String, AssociationRule> load(AssociationModel associationModel){
				return EntityUtil.buildBiMap(associationModel.getAssociationRules());
			}
		});

	private static final LoadingCache<AssociationModel, BiMap<String, Item>> itemCache = CacheBuilder.newBuilder()
		.weakKeys()
		.build(new CacheLoader<AssociationModel, BiMap<String, Item>>(){

			@Override
			public BiMap<String, Item> load(AssociationModel associationModel){
				return EntityUtil.buildBiMap(associationModel.getItems());
			}
		});

	private static final LoadingCache<AssociationModel, BiMap<String, Itemset>> itemsetCache = CacheBuilder.newBuilder()
		.weakKeys()
		.build(new CacheLoader<AssociationModel, BiMap<String, Itemset>>(){

			@Override
			public BiMap<String, Itemset> load(AssociationModel associationModel){
				return EntityUtil.buildBiMap(associationModel.getItemsets());
			}
		});

	private static final LoadingCache<AssociationModel, BiMap<String, String>> itemValueCache = CacheBuilder.newBuilder()
		.weakKeys()
		.build(new CacheLoader<AssociationModel, BiMap<String, String>>(){

			@Override
			public BiMap<String, String> load(AssociationModel associationModel){
				return ImmutableBiMap.copyOf(parseItemValues(associationModel));
			}
		});
}