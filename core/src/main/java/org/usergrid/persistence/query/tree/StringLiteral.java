/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.persistence.query.tree;

import static org.apache.commons.lang.StringUtils.removeEnd;

import org.antlr.runtime.ClassicToken;
import org.antlr.runtime.Token;

/**
 * @author tnine
 * 
 */
public class StringLiteral extends Literal<String> {

    private String value;
    private String finishValue;

    /**
     * @param t
     */
    public StringLiteral(Token t) {
        super(t);
        value = t.getText();
        value = value.substring(1, value.length() - 1);

        parseValue(value);
    }

    public StringLiteral(String value) {
        super(new ClassicToken(0, value));
        parseValue(value);

    }

    /**
     * Parse the value and set the optional end value
     * @param value
     */
    private void parseValue(String value) {
       
        this.value = value;

        if ("*".equals(value)) {
            this.value = null;
            this.finishValue = null;
            return;
        }

        if (value != null && value.endsWith("*")) {
            this.value = removeEnd(value.toString(), "*");

            finishValue = this.value + "\uFFFF";
           
        }
        //set the end value to the same as the start value
        else{
            finishValue = value;
        }
        
    }

    /**
     * If this were a string literal
     * 
     * @return
     */
    public String getEndValue() {
        return this.finishValue;
    }

    public String getValue() {
        return this.value;
    }

}
