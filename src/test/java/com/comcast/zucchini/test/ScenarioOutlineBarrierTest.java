/**
 * Copyright 2014 Comcast Cable Communications Management, LLC
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.comcast.zucchini.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.comcast.zucchini.AbstractZucchiniTest;
import com.comcast.zucchini.TestContext;
import com.comcast.zucchini.ZucchiniOutput;

import cucumber.api.CucumberOptions;

@CucumberOptions(features = { "src/test/resources" }, tags = { "@OUTLINE-BARRIER" })
@ZucchiniOutput()
public class ScenarioOutlineBarrierTest extends AbstractZucchiniTest {
    
    public static int numContexts = 3;
    
    @Override
    public List<TestContext> getTestContexts() {
        List<TestContext> contexts = new ArrayList<TestContext>();
        
        for (int i = 0; i < numContexts; i++) {
            contexts.add(new TestContext(String.format("ThreadIdx[%d]", i), new HashMap<String, Object>()));
        }
        
        return contexts;
    }
    
    @Override
    public boolean canBarrier() {
        return true;
    }
}
