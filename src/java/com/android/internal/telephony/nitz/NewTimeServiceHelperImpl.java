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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timedetector.PhoneTimeSuggestion;
import android.app.timedetector.TimeDetector;
import android.app.timezonedetector.PhoneTimeZoneSuggestion;
import android.app.timezonedetector.TimeZoneDetector;
import android.content.Context;
import android.util.LocalLog;
import android.util.TimestampedValue;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * The real implementation of {@link NewTimeServiceHelper}.
 */
public final class NewTimeServiceHelperImpl implements NewTimeServiceHelper {

    private final int mPhoneId;
    private final TimeDetector mTimeDetector;
    private final TimeZoneDetector mTimeZoneDetector;

    private final LocalLog mTimeZoneLog = new LocalLog(30, false /* mUseLocalTimestamps */);
    private final LocalLog mTimeLog = new LocalLog(30, false /* mUseLocalTimestamps */);

    /**
     * Records the last time zone suggestion made. Used to avoid sending duplicate suggestions to
     * the time zone service. The value can be {@code null} to indicate no previous suggestion has
     * been made.
     */
    @NonNull
    private PhoneTimeZoneSuggestion mLastSuggestedTimeZone;

    public NewTimeServiceHelperImpl(@NonNull Phone phone) {
        mPhoneId = phone.getPhoneId();
        Context context = Objects.requireNonNull(phone.getContext());
        mTimeDetector = Objects.requireNonNull(context.getSystemService(TimeDetector.class));
        mTimeZoneDetector =
                Objects.requireNonNull(context.getSystemService(TimeZoneDetector.class));
    }

    @Override
    public void suggestDeviceTime(@NonNull PhoneTimeSuggestion phoneTimeSuggestion) {
        mTimeLog.log("Sending time suggestion: " + phoneTimeSuggestion);

        Objects.requireNonNull(phoneTimeSuggestion);

        if (phoneTimeSuggestion.getUtcTime() != null) {
            TimestampedValue<Long> utcTime = phoneTimeSuggestion.getUtcTime();
            TelephonyMetrics.getInstance().writeNITZEvent(mPhoneId, utcTime.getValue());
        }
        mTimeDetector.suggestPhoneTime(phoneTimeSuggestion);
    }

    @Override
    public void maybeSuggestDeviceTimeZone(@NonNull PhoneTimeZoneSuggestion newSuggestion) {
        Objects.requireNonNull(newSuggestion);

        PhoneTimeZoneSuggestion oldSuggestion = mLastSuggestedTimeZone;
        if (shouldSendNewTimeZoneSuggestion(oldSuggestion, newSuggestion)) {
            mTimeZoneLog.log("Suggesting time zone update: " + newSuggestion);
            mTimeZoneDetector.suggestPhoneTimeZone(newSuggestion);
            mLastSuggestedTimeZone = newSuggestion;
        }
    }

    private static boolean shouldSendNewTimeZoneSuggestion(
            @Nullable PhoneTimeZoneSuggestion oldSuggestion,
            @NonNull PhoneTimeZoneSuggestion newSuggestion) {
        if (oldSuggestion == null) {
            // No previous suggestion.
            return true;
        }
        // This code relies on PhoneTimeZoneSuggestion.equals() to only check meaningful fields.
        return !Objects.equals(newSuggestion, oldSuggestion);
    }

    @Override
    public void dumpLogs(IndentingPrintWriter ipw) {
        ipw.println("NewTimeServiceHelperImpl:");
        ipw.increaseIndent();
        ipw.println("Time Logs:");
        ipw.increaseIndent();
        mTimeLog.dump(ipw);
        ipw.decreaseIndent();

        ipw.println("Time zone Logs:");
        ipw.increaseIndent();
        mTimeZoneLog.dump(ipw);
        ipw.decreaseIndent();
        ipw.decreaseIndent();
    }

    @Override
    public void dumpState(PrintWriter pw) {
        pw.println(" NewTimeServiceHelperImpl.mLastSuggestedTimeZone=" + mLastSuggestedTimeZone);
    }
}
