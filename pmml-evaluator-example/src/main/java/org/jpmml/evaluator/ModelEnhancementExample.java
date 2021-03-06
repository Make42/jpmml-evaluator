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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.internal.Lists;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.InlineTable;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.Model;
import org.dmg.pmml.ModelVerification;
import org.dmg.pmml.OutputField;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Row;
import org.dmg.pmml.VerificationField;
import org.dmg.pmml.VerificationFields;
import org.jpmml.manager.InvalidFeatureException;
import org.jpmml.manager.PMMLManager;
import org.jpmml.model.ImportFilter;
import org.jpmml.model.JAXBUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

public class ModelEnhancementExample extends Example {

	@Parameter (
		names = {"--model"},
		description = "PMML file",
		required = true
	)
	private File model = null;

	@Parameter (
		names = {"--data-xmlns"},
		description = "XML namespace URI for data elements"
	)
	private String dataURI = null;

	@Parameter (
		names = {"--verification"},
		description = "Verification data CSV file",
		required = true
	)
	private File verification = null;


	static
	public void main(String... args) throws Exception {
		execute(ModelEnhancementExample.class, args);
	}

	@Override
	public void execute() throws Exception {
		PMML pmml;

		InputStream is = new FileInputStream(this.model);

		try {
			Source source = ImportFilter.apply(new InputSource(is));

			pmml = JAXBUtil.unmarshalPMML(source);
		} finally {
			is.close();
		}

		CsvUtil.Table verificationTable = CsvUtil.readTable(this.verification);

		PMMLManager pmmlManager = new PMMLManager(pmml);

		ModelEvaluator<?> modelEvaluator = (ModelEvaluator<?>)pmmlManager.getModelManager(ModelEvaluatorFactory.getInstance());

		Model model = modelEvaluator.getModel();

		ModelVerification modelVerification = model.getModelVerification();
		if(modelVerification != null){
			throw new InvalidFeatureException(modelVerification);
		}

		modelVerification = new ModelVerification();

		List<String> tagNames = Lists.newArrayList();

		VerificationFields verificationFields = new VerificationFields();

		header:
		{
			List<String> headerRow = verificationTable.get(0);

			for(int column = 0; column < headerRow.size(); column++){
				String field = headerRow.get(column);

				FieldName name = FieldName.create(field);

				MiningField miningField = modelEvaluator.getMiningField(name);
				if(miningField == null){
					OutputField outputField = modelEvaluator.getOutputField(name);

					if(outputField == null){
						tagNames.add(null);

						continue;
					}
				}

				VerificationField verificationField = new VerificationField(field);

				if(field.contains(" ")){
					verificationField = verificationField.withColumn(field.replace(" ", "_x0020_"));

					tagNames.add(verificationField.getColumn());
				} else

				{
					tagNames.add(field);
				}

				verificationFields = verificationFields.withVerificationFields(verificationField);
			}
		}

		modelVerification = modelVerification.withVerificationFields(verificationFields);

		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
		documentBuilderFactory.setNamespaceAware(true);

		DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();

		InlineTable inlineTable = new InlineTable();

		body:
		for(int i = 1; i < verificationTable.size(); i++){
			List<String> bodyRow = verificationTable.get(i);

			Row row = new Row();

			for(int column = 0; column < bodyRow.size(); column++){
				String tagName = tagNames.get(column);

				if(tagName == null){
					continue;
				}

				String value = bodyRow.get(column);

				if(("NA").equals(value) || ("N/A").equals(value)){
					continue;
				}

				Document document = documentBuilder.newDocument();

				Element element = document.createElementNS(this.dataURI, this.dataURI != null ? ("data:" + tagName) : tagName);
				element.setTextContent(value);

				row = row.withContent(element);

				documentBuilder.reset();
			}

			inlineTable = inlineTable.withRows(row);
		}

		modelVerification = modelVerification.withInlineTable(inlineTable);

		model.setModelVerification(modelVerification);

		OutputStream os = new FileOutputStream(this.model);

		try {
			Result result = new StreamResult(os);

			JAXBUtil.marshalPMML(pmml, result);
		} finally {
			os.close();
		}
	}
}