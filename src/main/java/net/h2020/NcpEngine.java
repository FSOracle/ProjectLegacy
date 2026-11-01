package net.h2020;
import net.h2020.api.Module;
interface NcpEngine {
    void load();
    void enable();
    void disable();
    Module watchdogModule();
}
