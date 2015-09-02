/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.tck;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.vm.TruffleVM;
import java.io.IOException;
import java.util.Random;
import static org.junit.Assert.*;
import org.junit.Test;

/**
 * A collection of tests that can certify language implementation to be compliant with most recent
 * requirements of the Truffle infrastructure and tooling. Subclass, implement abstract methods and
 * include in your test suite.
 */
public abstract class TruffleTCK {
    private TruffleVM tckVM;

    protected TruffleTCK() {
    }

    /**
     * This methods is called before first test is executed. It's purpose is to set a TruffleVM with
     * your language up, so it is ready for testing.
     * {@link TruffleVM#eval(com.oracle.truffle.api.source.Source) Execute} any scripts you need,
     * and prepare global symbols with proper names. The symbols will then be looked up by the
     * infrastructure (using the names provided by you from methods like {@link #plusInt()}) and
     * used for internal testing.
     *
     * @return initialized Truffle virtual machine
     * @throws java.lang.Exception thrown when the VM preparation fails
     */
    protected abstract TruffleVM prepareVM() throws Exception;

    /**
     * Mimetype associated with your language. The mimetype will be passed to
     * {@link TruffleVM#eval(com.oracle.truffle.api.source.Source)} method of the
     * {@link #prepareVM() created TruffleVM}.
     *
     * @return mime type of the tested language
     */
    protected abstract String mimeType();

    /**
     * Name of function which will return value 42 as a number. The return value of the method
     * should be instance of {@link Number} and its {@link Number#intValue()} should return
     * <code>42</code>.
     *
     * @return name of globally exported symbol
     */
    protected abstract String fourtyTwo();

    /**
     * Name of a function that returns <code>null</code>. Truffle languages are encouraged to have
     * their own type representing <code>null</code>, but when such value is returned from
     * {@link TruffleVM#eval}, it needs to be converted to real Java <code>null</code> by sending a
     * foreign access <em>isNull</em> message. There is a test to verify it is really true.
     *
     * @return name of globally exported symbol
     */
    protected abstract String returnsNull();

    /**
     * Name of function to add two integer values together. The symbol will be invoked with two
     * parameters of type {@link Integer} and expects result of type {@link Number} which's
     * {@link Number#intValue()} is equivalent of <code>param1 + param2</code>.
     *
     * @return name of globally exported symbol
     */
    protected abstract String plusInt();

    /**
     * Name of a function in your language to perform a callback to foreign function. Your function
     * should prepare two numbers (18 and 32) and apply them to the function passed in as an
     * argument of your function. It should then add 10 to the returned value and return the result
     * back to its caller.
     *
     * @return name of globally exported symbol
     */
    protected abstract String applyNumbers();

    /**
     * Name of a function that counts number of its invocations in current {@link TruffleVM}
     * context. Your function should somehow keep a counter to remember number of its invocations
     * and always increment it. The first invocation should return <code>1</code>, the second
     * <code>2</code> and so on. The returned values are expected to be instances of {@link Number}.
     * <p>
     * The function will be used to test that two instances of your language can co-exist next to
     * each other. Without being mutually influenced.
     *
     * @return name of globally expected symbol
     */
    protected abstract String countInvocations();

    /**
     * Return a code snippet that is invalid in your language. Its
     * {@link TruffleVM#eval(com.oracle.truffle.api.source.Source) evaluation} should fail and yield
     * an exception.
     *
     * @return code snippet invalid in the tested language
     */
    protected abstract String invalidCode();

    private TruffleVM vm() throws Exception {
        if (tckVM == null) {
            tckVM = prepareVM();
        }
        return tckVM;
    }

    //
    // The tests
    //

    @Test
    public void testFortyTwo() throws Exception {
        TruffleVM.Symbol fourtyTwo = findGlobalSymbol(fourtyTwo());

        Object res = fourtyTwo.invoke(null).get();

        assert res instanceof Number : "should yield a number, but was: " + res;

        Number n = (Number) res;

        assert 42 == n.intValue() : "The value is 42 =  " + n.intValue();
    }

    @Test
    public void testNull() throws Exception {
        if (getClass() == TruffleTCK.class) {
            return;
        }
        TruffleVM.Symbol retNull = findGlobalSymbol(returnsNull());

        Object res = retNull.invoke(null).get();

        assertNull("Should yield real Java null", res);
    }

    @Test
    public void testPlusWithInts() throws Exception {
        Random r = new Random();
        int a = r.nextInt(100);
        int b = r.nextInt(100);

        TruffleVM.Symbol plus = findGlobalSymbol(plusInt());

        Object res = plus.invoke(null, a, b).get();

        assert res instanceof Number : "+ on two ints should yield a number, but was: " + res;

        Number n = (Number) res;

        assert a + b == n.intValue() : "The value is correct: (" + a + " + " + b + ") =  " + n.intValue();
    }

    @Test(expected = IOException.class)
    public void testInvalidTestMethod() throws Exception {
        String mime = mimeType();
        String code = invalidCode();
        Object ret = vm().eval(Source.fromText(code, "Invalid code").withMimeType(mime)).get();
        fail("Should yield IOException, but returned " + ret);
    }

    @Test
    public void testMaxOrMinValue() throws Exception {
        TruffleVM.Symbol apply = findGlobalSymbol(applyNumbers());

        Object res = apply.invoke(null, new MaxMinObject(true)).get();

        assert res instanceof Number : "result should be a number: " + res;

        Number n = (Number) res;

        assert 42 == n.intValue() : "32 > 18 and plus 10";
    }

    @Test
    public void testMaxOrMinValue2() throws Exception {
        TruffleVM.Symbol apply = findGlobalSymbol(applyNumbers());

        Object res = apply.invoke(null, new MaxMinObject(false)).get();

        assert res instanceof Number : "result should be a number: " + res;

        Number n = (Number) res;

        assert 28 == n.intValue() : "18 < 32 and plus 10";
    }

    @Test
    public void testCoExistanceOfMultipleLanguageInstances() throws Exception {
        final String countMethod = countInvocations();
        TruffleVM.Symbol count1 = findGlobalSymbol(countMethod);
        TruffleVM vm1 = tckVM;
        tckVM = null; // clean-up
        TruffleVM.Symbol count2 = findGlobalSymbol(countMethod);
        TruffleVM vm2 = tckVM;

        assertNotSame("Two virtual machines allocated", vm1, vm2);

        int prev1 = 0;
        int prev2 = 0;
        Random r = new Random();
        for (int i = 0; i < 10; i++) {
            int quantum = r.nextInt(10);
            for (int j = 0; j < quantum; j++) {
                Object res = count1.invoke(null).get();
                assert res instanceof Number : "expecting number: " + res;
                assert ((Number) res).intValue() == ++prev1 : "expecting " + prev1 + " but was " + res;
            }
            for (int j = 0; j < quantum; j++) {
                Object res = count2.invoke(null).get();
                assert res instanceof Number : "expecting number: " + res;
                assert ((Number) res).intValue() == ++prev2 : "expecting " + prev2 + " but was " + res;
            }
            assert prev1 == prev2 : "At round " + i + " the same number of invocations " + prev1 + " vs. " + prev2;
        }

    }

    private TruffleVM.Symbol findGlobalSymbol(String name) throws Exception {
        TruffleVM.Symbol s = vm().findGlobalSymbol(name);
        assert s != null : "Symbol " + name + " is not found!";
        return s;
    }
}
