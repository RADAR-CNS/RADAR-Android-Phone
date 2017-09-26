/*
 * Copyright 2017 The Hyve
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

package org.radarcns.phone;

import org.radarcns.android.device.DeviceTopics;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.passive.phone.PhoneCall;
import org.radarcns.passive.phone.PhoneSms;
import org.radarcns.passive.phone.PhoneSmsUnread;
import org.radarcns.topic.AvroTopic;

/** Topic manager for topics concerning the Phone sensors. */
public class PhoneLogTopics extends DeviceTopics {
    private static final Object syncObject = new Object();
    private static PhoneLogTopics instance = null;

    private final AvroTopic<ObservationKey, PhoneCall> callTopic;
    private final AvroTopic<ObservationKey, PhoneSms> smsTopic;
    private final AvroTopic<ObservationKey, PhoneSmsUnread> smsUnreadTopic;

    public static PhoneLogTopics getInstance() {
        synchronized (syncObject) {
            if (instance == null) {
                instance = new PhoneLogTopics();
            }
            return instance;
        }
    }

    private PhoneLogTopics() {
        callTopic = createTopic("android_phone_call",
                PhoneCall.getClassSchema(),
                PhoneCall.class);
        smsTopic = createTopic("android_phone_sms",
                PhoneSms.getClassSchema(),
                PhoneSms.class);
        smsUnreadTopic = createTopic("android_phone_sms_unread",
                PhoneSmsUnread.getClassSchema(),
                PhoneSmsUnread.class);
    }

    public AvroTopic<ObservationKey, PhoneCall> getCallTopic() {
        return callTopic;
    }

    public AvroTopic<ObservationKey, PhoneSms> getSmsTopic() {
        return smsTopic;
    }

    public AvroTopic<ObservationKey, PhoneSmsUnread> getSmsUnreadTopic() {
        return smsUnreadTopic;
    }
}
