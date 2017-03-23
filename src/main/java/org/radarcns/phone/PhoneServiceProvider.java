package org.radarcns.phone;

import android.os.Parcelable;

import org.radarcns.android.device.DeviceServiceProvider;

import java.util.Arrays;
import java.util.List;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

public class PhoneServiceProvider extends DeviceServiceProvider<PhoneState> {
    @Override
    public Class<?> getServiceClass() {
        return PhoneSensorsService.class;
    }

    @Override
    public Parcelable.Creator<PhoneState> getStateCreator() {
        return PhoneState.CREATOR;
    }

    @Override
    public String getDisplayName() {
        return getActivity().getString(R.string.phoneServiceDisplayName);
    }

    @Override
    public List<String> needsPermissions() {
        return Arrays.asList(ACCESS_COARSE_LOCATION, WRITE_EXTERNAL_STORAGE);
    }
}
