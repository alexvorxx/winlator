package com.winlator;
// This interface is used by utility on termux side.
interface ICmdEntryInterface {
    void windowChanged(in Surface surface, String name);
    ParcelFileDescriptor getXConnection();
    ParcelFileDescriptor getLogcatOutput();
}
