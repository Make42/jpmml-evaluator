/*
 * Copyright (c) 2014 Villu Ruusmann
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

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.dmg.pmml.FieldName;
import org.dmg.pmml.Model;
import org.dmg.pmml.PMML;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SelectAllTest extends ModelEvaluatorTest {

	@Test
	public void evaluate() throws Exception {
		CountingModelEvaluatorFactory evaluatorFactory = new CountingModelEvaluatorFactory();

		Evaluator evaluator = createModelEvaluator(evaluatorFactory);

		Map<FieldName, ?> arguments = createArguments("sepal_length", 5.1d, "sepal_width", 3.5d, "petal_length", 1.4d, "petal_width", 0.2d);

		Map<FieldName, ?> result = evaluator.evaluate(arguments);

		assertEquals(1, evaluatorFactory.getMiningModelCount());
		assertEquals(5, evaluatorFactory.getTreeModelCount());

		assertEquals(1, result.size());

		Collection<?> species = (Collection<?>)result.get(new FieldName("species"));

		assertEquals(5, species.size());

		for(Object value : species){
			assertTrue((value instanceof Computable) & (value instanceof HasEntityId) & (value instanceof HasProbability));
		}

		assertEquals(Arrays.asList("setosa", "setosa", "setosa", "setosa", "versicolor"), EvaluatorUtil.decode(species));
	}

	static
	private class CountingModelEvaluatorFactory extends ModelEvaluatorFactory {

		private int miningModelCount = 0;

		private int treeModelCount = 0;


		@Override
		public ModelEvaluator<? extends Model> getModelManager(PMML pmml, Model model){
			ModelEvaluator<?> result = super.getModelManager(pmml, model);

			if(result instanceof MiningModelEvaluator){
				this.miningModelCount++;
			} else

			if(result instanceof TreeModelEvaluator){
				this.treeModelCount++;
			} else

			{
				throw new AssertionError();
			}

			return result;
		}

		public int getMiningModelCount(){
			return this.miningModelCount;
		}

		public int getTreeModelCount(){
			return this.treeModelCount;
		}
	}
}