/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.uicc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.test.TestLooper;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.uicc.IccRecords.OperatorPlmnInfo;
import com.android.internal.telephony.uicc.IccRecords.PlmnNetworkName;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SIMRecordsTest extends TelephonyTest {
    private static final List<String> SHORT_FPLMNS_LIST = Arrays.asList("12345", "123456", "09876");
    private static final List<String> LONG_FPLMNS_LIST =
            Arrays.asList("12345", "123456", "09876", "123456", "098237");
    private static final List<String> EMPTY_FPLMN_LIST = new ArrayList<>();
    private static final int EF_SIZE = 12;
    private static final int MAX_NUM_FPLMN = 4;

    @Mock private IccFileHandler mFhMock;

    private SIMRecordsUT mSIMRecordsUT;
    private TestLooper mTestLooper;
    private Handler mTestHandler;
    private SIMRecordsReceiver mSIMRecordsReceiver;
    private SIMRecords mSIMRecord;

    private class SIMRecordsUT extends SIMRecords {
        SIMRecordsUT(UiccCardApplication app, Context c,
                CommandsInterface ci, IccFileHandler mFhMock) {
            super(app, c, ci);
            mFh = mFhMock;
        }
    }

    private class SIMRecordsReceiver {
        private SIMRecordsUT mSIMRecordsUT;

        public void set(SIMRecordsUT m) {
            mSIMRecordsUT = m;
        }

        public SIMRecordsUT get() {
            return mSIMRecordsUT;
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mTestLooper = new TestLooper();
        mTestHandler = new Handler(mTestLooper.getLooper());
        mSIMRecordsReceiver = new SIMRecordsReceiver();
        mTestHandler.post(
                () -> {
                    SIMRecordsUT mSIMRecords =
                            new SIMRecordsUT(
                                    mUiccCardApplication3gpp,
                                    mContext,
                                    mSimulatedCommands,
                                    mFhMock);
                    mSIMRecordsReceiver.set(mSIMRecords);
                });
        mTestLooper.dispatchAll();
        mSIMRecordsUT = mSIMRecordsReceiver.get();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testSetForbiddenPlmnsPad() {
        setUpSetForbiddenPlmnsTests();
        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.setForbiddenPlmns(message, SHORT_FPLMNS_LIST);
        mTestLooper.dispatchAll();

        byte[] encodedFplmn = IccUtils.encodeFplmns(SHORT_FPLMNS_LIST, EF_SIZE);
        AsyncResult ar = (AsyncResult) message.obj;
        assertEquals(SHORT_FPLMNS_LIST.size(), (int) ar.result);
        verify(mFhMock).getEFTransparentRecordSize(eq(mSIMRecordsUT.EF_FPLMN), any(Message.class));
        verify(mFhMock).updateEFTransparent(
                eq(mSIMRecordsUT.EF_FPLMN), eq(encodedFplmn), any(Message.class));
    }

    @Test
    public void testSetForbiddenPlmnsTruncate() {
        setUpSetForbiddenPlmnsTests();
        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.setForbiddenPlmns(message, LONG_FPLMNS_LIST);
        mTestLooper.dispatchAll();

        byte[] encodedFplmn = IccUtils.encodeFplmns(LONG_FPLMNS_LIST, EF_SIZE);
        AsyncResult ar = (AsyncResult) message.obj;
        assertEquals(MAX_NUM_FPLMN, (int) ar.result);
        verify(mFhMock).getEFTransparentRecordSize(eq(mSIMRecordsUT.EF_FPLMN), any(Message.class));
        verify(mFhMock).updateEFTransparent(
                eq(mSIMRecordsUT.EF_FPLMN), eq(encodedFplmn), any(Message.class));
    }

    @Test
    public void testSetForbiddenPlmnsClear() {
        setUpSetForbiddenPlmnsTests();
        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.setForbiddenPlmns(message, EMPTY_FPLMN_LIST);
        mTestLooper.dispatchAll();

        byte[] encodedFplmn = IccUtils.encodeFplmns(EMPTY_FPLMN_LIST, EF_SIZE);
        AsyncResult ar = (AsyncResult) message.obj;
        assertEquals(EMPTY_FPLMN_LIST.size(), (int) ar.result);
        verify(mFhMock).getEFTransparentRecordSize(eq(mSIMRecordsUT.EF_FPLMN), any(Message.class));
        verify(mFhMock).updateEFTransparent(
                eq(mSIMRecordsUT.EF_FPLMN), eq(encodedFplmn), any(Message.class));
    }

    private void setUpSetForbiddenPlmnsTests() {
        doAnswer(
            invocation -> {
                Message response = invocation.getArgument(1);
                AsyncResult.forMessage(response, EF_SIZE, null);
                response.sendToTarget();
                return null;
            })
            .when(mFhMock)
            .getEFTransparentRecordSize(anyInt(), any(Message.class));
        doAnswer(
            invocation -> {
                Message response = invocation.getArgument(2);
                AsyncResult.forMessage(response, true, null);
                response.sendToTarget();
                return null;
            })
            .when(mFhMock)
            .updateEFTransparent(anyInt(), any(byte[].class), any(Message.class));
    }

    @Test
    public void testGetForbiddenPlmns() {
        doAnswer(
            invocation -> {
                Message response = invocation.getArgument(1);
                byte[] encodedFplmn = IccUtils.encodeFplmns(SHORT_FPLMNS_LIST, EF_SIZE);
                AsyncResult.forMessage(response, encodedFplmn, null);
                response.sendToTarget();
                return null;
            })
            .when(mFhMock)
            .loadEFTransparent(eq(SIMRecords.EF_FPLMN), any(Message.class));

        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.getForbiddenPlmns(message);
        mTestLooper.dispatchAll();

        AsyncResult ar = (AsyncResult) message.obj;
        assertNull(ar.exception);
        assertEquals(SHORT_FPLMNS_LIST.toArray(new String[SHORT_FPLMNS_LIST.size()]),
                (String[]) ar.result);
    }

    @Test
    public void testGetForbiddenPlmnsException() {
        doAnswer(
            invocation -> {
                Message response = invocation.getArgument(1);
                AsyncResult.forMessage(response, null, new CommandException(
                        CommandException.Error.OPERATION_NOT_ALLOWED));
                response.sendToTarget();
                return null;
            })
            .when(mFhMock)
            .loadEFTransparent(eq(SIMRecords.EF_FPLMN), any(Message.class));

        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.getForbiddenPlmns(message);
        mTestLooper.dispatchAll();

        AsyncResult ar = (AsyncResult) message.obj;
        assertTrue(ar.exception instanceof CommandException);
        assertTrue(((CommandException) ar.exception).getCommandError() ==
                CommandException.Error.OPERATION_NOT_ALLOWED);
        assertNull(ar.result);
    }

    @Test
    public void testGetForbiddenPlmnsNull() {
        doAnswer(
            invocation -> {
                Message response = invocation.getArgument(1);
                AsyncResult.forMessage(response);
                response.sendToTarget();
                return null;
            })
            .when(mFhMock)
            .loadEFTransparent(eq(SIMRecords.EF_FPLMN), any(Message.class));

        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.getForbiddenPlmns(message);
        mTestLooper.dispatchAll();

        AsyncResult ar = (AsyncResult) message.obj;
        assertNull(ar.exception);
        assertNull(ar.result);
    }

    @Test
    public void testGetForbiddenPlmnsEmptyList() {
        doAnswer(
            invocation -> {
                Message response = invocation.getArgument(1);
                byte[] encodedFplmn = IccUtils.encodeFplmns(EMPTY_FPLMN_LIST, EF_SIZE);
                AsyncResult.forMessage(response, encodedFplmn, null);
                response.sendToTarget();
                return null;
            })
            .when(mFhMock)
            .loadEFTransparent(eq(SIMRecords.EF_FPLMN), any(Message.class));

        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.getForbiddenPlmns(message);
        mTestLooper.dispatchAll();

        AsyncResult ar = (AsyncResult) message.obj;
        assertNull(ar.exception);
        assertEquals(EMPTY_FPLMN_LIST.toArray(new String[EMPTY_FPLMN_LIST.size()]),
                (String[]) ar.result);
    }

    @Test
    public void testGetForbiddenPlmnsInvalidLength() {
        doAnswer(
            invocation -> {
                Message response = invocation.getArgument(1);
                AsyncResult.forMessage(response, new byte[] { (byte) 0xFF, (byte) 0xFF }, null);
                response.sendToTarget();
                return null;
            })
            .when(mFhMock)
            .loadEFTransparent(eq(SIMRecords.EF_FPLMN), any(Message.class));

        Message message = Message.obtain(mTestHandler);
        mSIMRecordsUT.getForbiddenPlmns(message);
        mTestLooper.dispatchAll();

        AsyncResult ar = (AsyncResult) message.obj;
        assertNull(ar.exception);
        assertNull(ar.result);
    }

    @Test
    public void testGetEfPnn() {
        ArrayList<byte[]> rawPnns = new ArrayList<byte[]>();
        List<PlmnNetworkName> targetPnns = new ArrayList<PlmnNetworkName>();

        String name = "Test 1";
        rawPnns.add(encodePnn(name));
        targetPnns.add(new PlmnNetworkName(name, null));
        name = "Test 2";
        rawPnns.add(encodePnn(name));
        targetPnns.add(new PlmnNetworkName(name, null));

        Message message = mSIMRecordsUT.obtainMessage(SIMRecords.EVENT_GET_PNN_DONE);
        AsyncResult ar = AsyncResult.forMessage(message, rawPnns, null);
        mSIMRecordsUT.handleMessage(message);
        List<PlmnNetworkName> parsedPnns = Arrays.asList(mSIMRecordsUT.getPnns());

        assertEquals(parsedPnns, targetPnns);
    }

    private static byte[] encodePnn(String name) {
        byte[] gsm7BitName = new byte[] {};
        try {
            gsm7BitName = GsmAlphabet.stringToGsm7BitPacked(name);
            gsm7BitName[0] = (byte) (name.length() % 8 | 0x80);
        } catch (Exception ex) {
            fail("SimRecordsTest: GsmAlphabet.stringToGsm7BitPacked() exception:" + ex);
        }

        byte[] encodedName = new byte[gsm7BitName.length + 2];
        encodedName[0] = 0x43;
        encodedName[1] = (byte) gsm7BitName.length;
        System.arraycopy(gsm7BitName, 0, encodedName, 2, gsm7BitName.length);

        return encodedName;
    }

    @Test
    public void testGetEfOpl() {
        ArrayList<byte[]> rawOpl = new ArrayList<byte[]>();
        List<OperatorPlmnInfo> targetOpl = new ArrayList<OperatorPlmnInfo>();

        // OperatorPlmnInfo 1
        String plmn = "123456";
        int lacTacStart = 0x0000;
        int lacTacEnd = 0xFFFE;
        int pnnIndex = 0;

        rawOpl.add(encodeOpl(plmn, lacTacStart, lacTacEnd, pnnIndex));
        targetOpl.add(new OperatorPlmnInfo(plmn, lacTacStart, lacTacEnd, pnnIndex));

        Message message = mSIMRecordsUT.obtainMessage(SIMRecords.EVENT_GET_OPL_DONE);
        AsyncResult ar = AsyncResult.forMessage(message, rawOpl, null);
        mSIMRecordsUT.handleMessage(message);
        List<OperatorPlmnInfo> parsedOpl = Arrays.asList(mSIMRecordsUT.getOpl());

        assertEquals(targetOpl, parsedOpl);

        // OperatorPlmnInfo 2
        plmn = "123DDD";
        lacTacStart = 0x0000;
        lacTacEnd = 0xFFFE;
        pnnIndex = 123;

        rawOpl.add(encodeOpl(plmn, lacTacStart, lacTacEnd, pnnIndex));
        targetOpl.add(new OperatorPlmnInfo(plmn, lacTacStart, lacTacEnd, pnnIndex));

        message = mSIMRecordsUT.obtainMessage(SIMRecords.EVENT_GET_OPL_DONE);
        ar = AsyncResult.forMessage(message, rawOpl, null);
        mSIMRecordsUT.handleMessage(message);
        parsedOpl = Arrays.asList(mSIMRecordsUT.getOpl());

        assertEquals(targetOpl, parsedOpl);

        // OperatorPlmnInfo 3
        plmn = "123";
        lacTacStart = 0x0000;
        lacTacEnd = 0xFFFE;
        pnnIndex = 123;

        rawOpl.add(encodeOpl(plmn, lacTacStart, lacTacEnd, pnnIndex));

        message = mSIMRecordsUT.obtainMessage(SIMRecords.EVENT_GET_OPL_DONE);
        ar = AsyncResult.forMessage(message, rawOpl, null);
        mSIMRecordsUT.handleMessage(message);
        parsedOpl = Arrays.asList(mSIMRecordsUT.getOpl());

        assertEquals(targetOpl, parsedOpl);
    }

    private byte[] encodeOpl(String plmn, int lacTacStart, int lacTacEnd, int pnnIndex) {
        byte[] data = new byte[8];

        if (plmn.length() == 5 || plmn.length() == 6) {
            IccUtils.stringToBcdPlmn(plmn, data, 0);
        } else {
            data[0] = (byte) 0xFF;
            data[1] = (byte) 0xFF;
            data[2] = (byte) 0xFF;
        }
        data[3] = (byte) (lacTacStart >>> 8);
        data[4] = (byte) lacTacStart;
        data[5] = (byte) (lacTacEnd >>> 8);
        data[6] = (byte) lacTacEnd;
        data[7] = (byte) pnnIndex;

        return data;
    }
}
