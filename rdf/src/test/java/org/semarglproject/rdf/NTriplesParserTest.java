/*
 * Copyright 2012 Lev Khomich
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

package org.semarglproject.rdf;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

public final class NTriplesParserTest {

    private TurtleSerializerSink semarglTurtleSink;
    private DataProcessor<Reader> dp;

    @BeforeClass
    public void cleanTargetDir() {
        NTriplesTestBundle.prepareTestDir();
        semarglTurtleSink = new TurtleSerializerSink();
        dp = new CharSource().streamingTo(new NTriplesParser().streamingTo(semarglTurtleSink)).build();
    }

    @DataProvider
    public Object[][] getTestFiles() throws IOException {
        return NTriplesTestBundle.getTestFiles();
    }

    @Test(dataProvider = "getTestFiles")
    public void NTriplesTestsTurtle(String caseName) throws Exception {
        NTriplesTestBundle.runTest(caseName, new NTriplesTestBundle.SaveToFileCallback() {
            @Override
            public void run(Reader input, String inputUri, Writer output) throws ParseException {
                semarglTurtleSink.setWriter(output);
                dp.process(input, inputUri);
            }
        });
    }

}
