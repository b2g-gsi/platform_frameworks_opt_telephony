/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.internal.telephony.nitz;

import static com.android.internal.telephony.NitzStateMachineTestSupport.ARBITRARY_SYSTEM_CLOCK_TIME;
import static com.android.internal.telephony.NitzStateMachineTestSupport.UNIQUE_US_ZONE_SCENARIO1;
import static com.android.internal.telephony.NitzStateMachineTestSupport.UNITED_KINGDOM_SCENARIO;
import static com.android.internal.telephony.NitzStateMachineTestSupport.createTimeSuggestionFromNitzSignal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.timedetector.PhoneTimeSuggestion;
import android.util.TimestampedValue;

import com.android.internal.telephony.NitzData;
import com.android.internal.telephony.NitzStateMachineTestSupport.FakeDeviceState;
import com.android.internal.telephony.NitzStateMachineTestSupport.Scenario;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.TimeZoneLookupHelper;
import com.android.internal.telephony.nitz.NewNitzStateMachineImpl.NitzSignalInputFilterPredicate;
import com.android.internal.telephony.nitz.service.PhoneTimeZoneSuggestion;
import com.android.internal.util.IndentingPrintWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

public class NewNitzStateMachineImplTest extends TelephonyTest {

    private static final int PHONE_ID = 99999;
    private static final PhoneTimeZoneSuggestion EMPTY_TIME_ZONE_SUGGESTION =
            new PhoneTimeZoneSuggestion(PHONE_ID);

    private FakeNewTimeServiceHelper mFakeNewTimeServiceHelper;
    private FakeDeviceState mFakeDeviceState;
    private TimeZoneSuggesterImpl mRealTimeZoneSuggester;

    private NewNitzStateMachineImpl mNitzStateMachineImpl;


    @Before
    public void setUp() throws Exception {
        TelephonyTest.logd("NewNitzStateMachineImplTest +Setup!");
        super.setUp("NewNitzStateMachineImplTest");

        // In tests we use a fake impls for NewTimeServiceHelper and DeviceState.
        mFakeDeviceState = new FakeDeviceState();
        mFakeNewTimeServiceHelper = new FakeNewTimeServiceHelper(mFakeDeviceState);

        // In tests we disable NITZ signal input filtering. The real NITZ signal filter is tested
        // independently. This makes constructing test data simpler: we can be sure the signals
        // won't be filtered for reasons like rate-limiting.
        NitzSignalInputFilterPredicate mFakeNitzSignalInputFilter = (oldSignal, newSignal) -> true;

        // In tests a real TimeZoneSuggesterImpl is used with the real TimeZoneLookupHelper and real
        // country time zone data. A fake device state is used (which allows tests to fake the
        // system clock / user settings). The tests can perform the expected lookups and confirm the
        // state machine takes the correct action. Picking real examples from the past is easier
        // than inventing countries / scenarios and configuring fakes.
        TimeZoneLookupHelper timeZoneLookupHelper = new TimeZoneLookupHelper();
        mRealTimeZoneSuggester = new TimeZoneSuggesterImpl(mFakeDeviceState, timeZoneLookupHelper);

        mNitzStateMachineImpl = new NewNitzStateMachineImpl(
                PHONE_ID, mFakeNitzSignalInputFilter, mRealTimeZoneSuggester,
                mFakeNewTimeServiceHelper);

        TelephonyTest.logd("NewNitzStateMachineImplTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void test_countryThenNitz() throws Exception {
        Scenario scenario = UNIQUE_US_ZONE_SCENARIO1;
        String networkCountryIsoCode = scenario.getNetworkCountryIsoCode();
        TimestampedValue<NitzData> nitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());

        // Capture expected results from the real suggester and confirm we can tell the difference
        // between them.
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion1 =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, networkCountryIsoCode, null /* nitzSignal */);
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion2 =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, networkCountryIsoCode, nitzSignal);
        assertNotNull(expectedTimeZoneSuggestion2);
        assertNotEquals(expectedTimeZoneSuggestion1, expectedTimeZoneSuggestion2);

        Script script = new Script()
                .initializeSystemClock(ARBITRARY_SYSTEM_CLOCK_TIME)
                .networkAvailable();

        // Simulate country being known.
        script.countryReceived(networkCountryIsoCode);

        script.verifyOnlyTimeZoneWasSuggestedAndReset(expectedTimeZoneSuggestion1);

        // Check NitzStateMachine exposed state.
        assertNull(mNitzStateMachineImpl.getCachedNitzData());

        // Simulate NITZ being received and verify the behavior.
        script.nitzReceived(nitzSignal);

        PhoneTimeSuggestion expectedTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, nitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedTimeSuggestion, expectedTimeZoneSuggestion2);

        // Check NitzStateMachine exposed state.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());
    }

    @Test
    public void test_nitzThenCountry() throws Exception {
        Scenario scenario = UNIQUE_US_ZONE_SCENARIO1;
        TimestampedValue<NitzData> nitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());

        String networkCountryIsoCode = scenario.getNetworkCountryIsoCode();

        // Capture test expectations from the real suggester and confirm we can tell the difference
        // between them.
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion1 =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, null /* countryIsoCode */, nitzSignal);
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion2 =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, networkCountryIsoCode, nitzSignal);
        assertNotEquals(expectedTimeZoneSuggestion1, expectedTimeZoneSuggestion2);

        Script script = new Script()
                .initializeSystemClock(ARBITRARY_SYSTEM_CLOCK_TIME)
                .networkAvailable();

        // Simulate receiving the NITZ signal.
        script.nitzReceived(nitzSignal);

        // Verify the state machine did the right thing.
        PhoneTimeSuggestion expectedTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, nitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedTimeSuggestion, expectedTimeZoneSuggestion1);

        // Check NitzStateMachine exposed state.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());

        // Simulate country being known and verify the behavior.
        script.countryReceived(networkCountryIsoCode)
                .verifyOnlyTimeZoneWasSuggestedAndReset(expectedTimeZoneSuggestion2);

        // Check NitzStateMachine exposed state.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());
    }

    @Test
    public void test_emptyCountryString_countryReceivedFirst() throws Exception {
        Scenario scenario = UNIQUE_US_ZONE_SCENARIO1;
        TimestampedValue<NitzData> nitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());

        Script script = new Script()
                .initializeSystemClock(ARBITRARY_SYSTEM_CLOCK_TIME)
                .networkAvailable();

        // Simulate an empty country being set.
        script.countryReceived("");

        // Nothing should be set. The country is not valid.
        script.verifyOnlyTimeZoneWasSuggestedAndReset(EMPTY_TIME_ZONE_SUGGESTION);

        // Check NitzStateMachine exposed state.
        assertNull(mNitzStateMachineImpl.getCachedNitzData());

        // Simulate receiving the NITZ signal.
        script.nitzReceived(nitzSignal);

        PhoneTimeSuggestion expectedTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, nitzSignal);
        // Capture output from the real suggester and confirm it meets the test's needs /
        // expectations.
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, "" /* countryIsoCode */, nitzSignal);
        assertEquals(PhoneTimeZoneSuggestion.TEST_NETWORK_OFFSET_ONLY,
                expectedTimeZoneSuggestion.getMatchType());
        assertEquals(PhoneTimeZoneSuggestion.MULTIPLE_ZONES_WITH_SAME_OFFSET,
                expectedTimeZoneSuggestion.getQuality());

        // Verify the state machine did the right thing.
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedTimeSuggestion, expectedTimeZoneSuggestion);

        // Check NitzStateMachine exposed state.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());
    }

    @Test
    public void test_emptyCountryStringUsTime_nitzReceivedFirst() throws Exception {
        Scenario scenario = UNIQUE_US_ZONE_SCENARIO1;
        TimestampedValue<NitzData> nitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());

        Script script = new Script()
                .initializeSystemClock(ARBITRARY_SYSTEM_CLOCK_TIME)
                .networkAvailable();

        // Simulate receiving the NITZ signal.
        script.nitzReceived(nitzSignal);

        // Verify the state machine did the right thing.
        // No time zone should be set. A NITZ signal by itself is not enough.
        PhoneTimeSuggestion expectedTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, nitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedTimeSuggestion, EMPTY_TIME_ZONE_SUGGESTION);

        // Check NitzStateMachine exposed state.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());

        // Simulate an empty country being set.
        script.countryReceived("");

        // Capture output from the real suggester and confirm it meets the test's needs /
        // expectations.
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, "" /* countryIsoCode */, nitzSignal);
        assertEquals(PhoneTimeZoneSuggestion.TEST_NETWORK_OFFSET_ONLY,
                expectedTimeZoneSuggestion.getMatchType());
        assertEquals(PhoneTimeZoneSuggestion.MULTIPLE_ZONES_WITH_SAME_OFFSET,
                expectedTimeZoneSuggestion.getQuality());

        // Verify the state machine did the right thing.
        script.verifyOnlyTimeZoneWasSuggestedAndReset(expectedTimeZoneSuggestion);

        // Check NitzStateMachine exposed state.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());
    }

    @Test
    public void test_airplaneModeClearsState() throws Exception {
        Scenario scenario = UNITED_KINGDOM_SCENARIO.mutableCopy();
        int timeStepMillis = (int) TimeUnit.HOURS.toMillis(3);

        Script script = new Script()
                .initializeSystemClock(ARBITRARY_SYSTEM_CLOCK_TIME)
                .networkAvailable();

        // Pre-flight: Simulate a device receiving signals that allow it to detect time and time
        // zone.
        TimestampedValue<NitzData> preFlightNitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());
        PhoneTimeSuggestion expectedPreFlightTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, preFlightNitzSignal);
        String preFlightCountryIsoCode = scenario.getNetworkCountryIsoCode();

        // Simulate receiving the NITZ signal and country.
        script.nitzReceived(preFlightNitzSignal)
                .countryReceived(preFlightCountryIsoCode);

        // Verify the state machine did the right thing.
        PhoneTimeZoneSuggestion expectedPreFlightTimeZoneSuggestion =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, preFlightCountryIsoCode, preFlightNitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedPreFlightTimeSuggestion, expectedPreFlightTimeZoneSuggestion);

        // Check state that NitzStateMachine must expose.
        assertEquals(preFlightNitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());

        // Boarded flight: Airplane mode turned on / time zone detection still enabled.
        // The NitzStateMachine must lose all state and stop having an opinion about time zone.

        // Simulate the passage of time and update the device realtime clock.
        scenario.incrementTime(timeStepMillis);
        script.incrementTime(timeStepMillis);

        // Simulate airplane mode being turned on.
        script.toggleAirplaneMode(true);

        // Verify the state machine did the right thing.
        // Check the time zone suggestion was withdrawn (time is not currently withdrawn).
        script.verifyOnlyTimeZoneWasSuggestedAndReset(EMPTY_TIME_ZONE_SUGGESTION);

        // Check state that NitzStateMachine must expose.
        assertNull(mNitzStateMachineImpl.getCachedNitzData());

        // During flight: Airplane mode turned off / time zone detection still enabled.
        // The NitzStateMachine still must not have an opinion about time zone / hold any state.

        // Simulate the passage of time and update the device realtime clock.
        scenario.incrementTime(timeStepMillis);
        script.incrementTime(timeStepMillis);

        // Simulate airplane mode being turned off.
        script.toggleAirplaneMode(false);

        // Verify nothing was suggested: The last suggestion was empty so nothing has changed.
        script.verifyNothingWasSuggested();

        // Check the state that NitzStateMachine must expose.
        assertNull(mNitzStateMachineImpl.getCachedNitzData());

        // Post flight: Device has moved and receives new signals.

        // Simulate the passage of time and update the device realtime clock.
        scenario.incrementTime(timeStepMillis);
        script.incrementTime(timeStepMillis);

        // Simulate the movement to the destination.
        scenario.changeCountry(UNIQUE_US_ZONE_SCENARIO1.getTimeZoneId(),
                UNIQUE_US_ZONE_SCENARIO1.getNetworkCountryIsoCode());

        // Simulate the device receiving NITZ signal and country again after the flight. Now the
        // NitzStateMachine should be opinionated again.
        TimestampedValue<NitzData> postFlightNitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());
        String postFlightCountryCode = scenario.getNetworkCountryIsoCode();
        script.countryReceived(postFlightCountryCode)
                .nitzReceived(postFlightNitzSignal);

        // Verify the state machine did the right thing.
        PhoneTimeSuggestion expectedPostFlightTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, postFlightNitzSignal);
        PhoneTimeZoneSuggestion expectedPostFlightTimeZoneSuggestion =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, postFlightCountryCode, postFlightNitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedPostFlightTimeSuggestion, expectedPostFlightTimeZoneSuggestion);

        // Check state that NitzStateMachine must expose.
        assertEquals(postFlightNitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());
    }

    /**
     * Confirm losing the network / NITZ doesn't clear country state.
     */
    @Test
    public void test_handleNetworkUnavailableClearsNetworkState() throws Exception {
        Scenario scenario = UNIQUE_US_ZONE_SCENARIO1.mutableCopy();
        int timeStepMillis = (int) TimeUnit.HOURS.toMillis(3);
        String countryIsoCode = scenario.getNetworkCountryIsoCode();

        Script script = new Script()
                .initializeSystemClock(ARBITRARY_SYSTEM_CLOCK_TIME)
                .networkAvailable();

        // Simulate a device receiving signals that allow it to detect time and time zone.
        TimestampedValue<NitzData> initialNitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());
        PhoneTimeSuggestion expectedInitialTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, initialNitzSignal);

        // Simulate receiving the NITZ signal and country.
        script.nitzReceived(initialNitzSignal)
                .countryReceived(countryIsoCode);

        // Verify the state machine did the right thing.
        PhoneTimeZoneSuggestion expectedInitialTimeZoneSuggestion =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, countryIsoCode, initialNitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedInitialTimeSuggestion, expectedInitialTimeZoneSuggestion);

        // Check state that NitzStateMachine must expose.
        assertEquals(initialNitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());

        // Simulate the passage of time and update the device realtime clock.
        scenario.incrementTime(timeStepMillis);
        script.incrementTime(timeStepMillis);

        // Simulate network being lost.
        script.networkUnavailable();

        // Verify the state machine did the right thing.
        // Check the "no NITZ" time zone suggestion is made (time is not currently withdrawn).
        PhoneTimeZoneSuggestion expectedMiddleTimeZoneSuggestion =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, countryIsoCode, null /* nitzSignal */);
        script.verifyOnlyTimeZoneWasSuggestedAndReset(expectedMiddleTimeZoneSuggestion);

        // Check state that NitzStateMachine must expose.
        assertNull(mNitzStateMachineImpl.getCachedNitzData());

        // Simulate the passage of time and update the device realtime clock.
        scenario.incrementTime(timeStepMillis);
        script.incrementTime(timeStepMillis);

        // Simulate the network being found.
        script.networkAvailable()
                .verifyNothingWasSuggested();

        // Check the state that NitzStateMachine must expose.
        assertNull(mNitzStateMachineImpl.getCachedNitzData());

        // Simulate the passage of time and update the device realtime clock.
        scenario.incrementTime(timeStepMillis);
        script.incrementTime(timeStepMillis);

        // Simulate the device receiving NITZ signal again. Now the NitzStateMachine should be
        // opinionated again.
        TimestampedValue<NitzData> finalNitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());
        script.nitzReceived(finalNitzSignal);

        // Verify the state machine did the right thing.
        PhoneTimeSuggestion expectedFinalTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, finalNitzSignal);
        PhoneTimeZoneSuggestion expectedFinalTimeZoneSuggestion =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, countryIsoCode, finalNitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedFinalTimeSuggestion, expectedFinalTimeZoneSuggestion);

        // Check state that NitzStateMachine must expose.
        assertEquals(finalNitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());
    }

    @Test
    public void test_countryUnavailableClearsTimeZoneSuggestion() throws Exception {
        Scenario scenario = UNIQUE_US_ZONE_SCENARIO1;
        TimestampedValue<NitzData> nitzSignal =
                scenario.createNitzSignal(mFakeDeviceState.elapsedRealtime());

        Script script = new Script()
                .initializeSystemClock(ARBITRARY_SYSTEM_CLOCK_TIME)
                .networkAvailable();

        // Simulate receiving the country and verify the state machine does the right thing.
        script.countryReceived(scenario.getNetworkCountryIsoCode());
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion1 =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, scenario.getNetworkCountryIsoCode(), null /* nitzSignal */);
        script.verifyOnlyTimeZoneWasSuggestedAndReset(expectedTimeZoneSuggestion1);

        // Simulate receiving an NITZ signal and verify the state machine does the right thing.
        script.nitzReceived(nitzSignal);
        PhoneTimeSuggestion expectedTimeSuggestion =
                createTimeSuggestionFromNitzSignal(PHONE_ID, nitzSignal);
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion2 =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, scenario.getNetworkCountryIsoCode(), nitzSignal);
        script.verifyTimeAndTimeZoneSuggestedAndReset(
                expectedTimeSuggestion, expectedTimeZoneSuggestion2);

        // Check state that NitzStateMachine must expose.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());

        // Simulate the country becoming unavailable and verify the state machine does the right
        // thing.
        script.countryUnavailable();
        PhoneTimeZoneSuggestion expectedTimeZoneSuggestion3 =
                mRealTimeZoneSuggester.getTimeZoneSuggestion(
                        PHONE_ID, null /* countryIsoCode */, nitzSignal);
        script.verifyOnlyTimeZoneWasSuggestedAndReset(expectedTimeZoneSuggestion3);

        // Check state that NitzStateMachine must expose.
        assertEquals(nitzSignal.getValue(), mNitzStateMachineImpl.getCachedNitzData());
    }

    /**
     * A "fluent" helper class allowing reuse of logic for test state initialization, simulation of
     * events, and verification of device state changes with self-describing method names.
     */
    private class Script {

        Script() {
            // Set initial fake device state.
            mFakeDeviceState.ignoreNitz = false;
            mFakeDeviceState.nitzUpdateDiffMillis = 2000;
            mFakeDeviceState.nitzUpdateSpacingMillis = 1000 * 60 * 10;
        }

        // Initialization methods for setting simulated device state, usually before simulation.

        Script initializeSystemClock(long timeMillis) {
            mFakeDeviceState.currentTimeMillis = timeMillis;
            return this;
        }

        // Simulation methods that are used by tests to pretend that something happens.

        Script incrementTime(int timeIncrementMillis) {
            mFakeDeviceState.simulateTimeIncrement(timeIncrementMillis);
            return this;
        }

        Script networkAvailable() {
            mNitzStateMachineImpl.handleNetworkAvailable();
            return this;
        }

        Script nitzReceived(TimestampedValue<NitzData> nitzSignal) {
            mNitzStateMachineImpl.handleNitzReceived(nitzSignal);
            return this;
        }

        Script networkUnavailable() {
            mNitzStateMachineImpl.handleNetworkUnavailable();
            return this;
        }

        Script countryUnavailable() {
            mNitzStateMachineImpl.handleCountryUnavailable();
            return this;
        }

        Script countryReceived(String countryIsoCode) {
            mNitzStateMachineImpl.handleCountryDetected(countryIsoCode);
            return this;
        }

        Script toggleAirplaneMode(boolean on) {
            mNitzStateMachineImpl.handleAirplaneModeChanged(on);
            return this;
        }

        // Verification methods.

        Script verifyNothingWasSuggested() {
            justVerifyTimeWasNotSuggested();
            justVerifyTimeWasNotSuggested();
            return this;
        }

        Script verifyOnlyTimeZoneWasSuggestedAndReset(PhoneTimeZoneSuggestion timeZoneSuggestion) {
            justVerifyTimeZoneWasSuggested(timeZoneSuggestion);
            justVerifyTimeWasNotSuggested();
            commitStateChanges();
            return this;
        }

        Script verifyTimeAndTimeZoneSuggestedAndReset(
                PhoneTimeSuggestion timeSuggestion, PhoneTimeZoneSuggestion timeZoneSuggestion) {
            justVerifyTimeZoneWasSuggested(timeZoneSuggestion);
            justVerifyTimeWasSuggested(timeSuggestion);
            commitStateChanges();
            return this;
        }

        private void justVerifyTimeWasNotSuggested() {
            mFakeNewTimeServiceHelper.suggestedTimes.assertHasNotBeenSet();
        }

        private void justVerifyTimeZoneWasSuggested(PhoneTimeZoneSuggestion timeZoneSuggestion) {
            mFakeNewTimeServiceHelper.suggestedTimeZones.assertHasBeenSet();
            mFakeNewTimeServiceHelper.suggestedTimeZones.assertLatestEquals(timeZoneSuggestion);
        }

        private void justVerifyTimeWasSuggested(PhoneTimeSuggestion timeSuggestion) {
            mFakeNewTimeServiceHelper.suggestedTimes.assertChangeCount(1);
            mFakeNewTimeServiceHelper.suggestedTimes.assertLatestEquals(timeSuggestion);
        }

        private void commitStateChanges() {
            mFakeNewTimeServiceHelper.commitState();
        }
    }

    /** Some piece of state that tests want to track. */
    private static class TestState<T> {
        private T mInitialValue;
        private LinkedList<T> mValues = new LinkedList<>();

        void init(T value) {
            mValues.clear();
            mInitialValue = value;
        }

        void set(T value) {
            mValues.addFirst(value);
        }

        boolean hasBeenSet() {
            return mValues.size() > 0;
        }

        void assertHasNotBeenSet() {
            assertFalse(hasBeenSet());
        }

        void assertHasBeenSet() {
            assertTrue(hasBeenSet());
        }

        void commitLatest() {
            if (hasBeenSet()) {
                mInitialValue = mValues.getLast();
                mValues.clear();
            }
        }

        void assertLatestEquals(T expected) {
            assertEquals(expected, getLatest());
        }

        void assertChangeCount(int expectedCount) {
            assertEquals(expectedCount, mValues.size());
        }

        public T getLatest() {
            if (hasBeenSet()) {
                return mValues.getFirst();
            }
            return mInitialValue;
        }
    }

    /**
     * A fake implementation of {@link NewTimeServiceHelper} that enables tests to detect what
     * {@link NewNitzStateMachineImpl} would do to a real device's state.
     */
    private static class FakeNewTimeServiceHelper implements NewTimeServiceHelper {

        private final FakeDeviceState mFakeDeviceState;

        // State we want to track.
        public final TestState<PhoneTimeSuggestion> suggestedTimes = new TestState<>();
        public final TestState<PhoneTimeZoneSuggestion> suggestedTimeZones = new TestState<>();

        FakeNewTimeServiceHelper(FakeDeviceState fakeDeviceState) {
            mFakeDeviceState = fakeDeviceState;
        }

        @Override
        public void suggestDeviceTime(PhoneTimeSuggestion timeSuggestion) {
            suggestedTimes.set(timeSuggestion);
            // The fake time service just uses the latest suggestion.
            mFakeDeviceState.currentTimeMillis = timeSuggestion.getUtcTime().getValue();
        }

        @Override
        public void maybeSuggestDeviceTimeZone(PhoneTimeZoneSuggestion timeZoneSuggestion) {
            suggestedTimeZones.set(timeZoneSuggestion);
        }

        @Override
        public void dumpLogs(IndentingPrintWriter ipw) {
            // No-op in tests
        }

        @Override
        public void dumpState(PrintWriter pw) {
            // No-op in tests
        }

        void commitState() {
            suggestedTimeZones.commitLatest();
            suggestedTimes.commitLatest();
        }
    }
}
