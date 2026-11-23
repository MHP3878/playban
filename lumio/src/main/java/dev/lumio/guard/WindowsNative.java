package dev.lumio.guard;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows only, through JNA (which Minecraft ships): the DLLs loaded into this game, and where each of
 * this game's threads started. A thread that starts in memory belonging to no DLL is the mark of code
 * mapped into the game by hand, the way injection clients load themselves. Only this game's own
 * process is looked at.
 */
final class WindowsNative {
    private static final int THREAD_QUERY_INFORMATION = 0x0040;
    private static final int THREAD_QUERY_SET_WIN32_START_ADDRESS = 9;

    interface Ntdll extends Library {
        int NtQueryInformationThread(WinNT.HANDLE thread, int infoClass, Pointer info, int length, IntByReference returned);
    }

    private static Ntdll ntdll;

    record Module(String path, long base, long size) {}

    private WindowsNative() {}

    static List<Module> modules() {
        List<Module> out = new ArrayList<>();
        Kernel32 k = Kernel32.INSTANCE;
        WinNT.HANDLE snap = k.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPMODULE, new WinDef.DWORD(0));
        if (snap == null || WinNT.INVALID_HANDLE_VALUE.equals(snap)) return out;
        try {
            Tlhelp32.MODULEENTRY32W e = new Tlhelp32.MODULEENTRY32W();
            if (k.Module32FirstW(snap, e)) {
                do {
                    out.add(new Module(e.szExePath(), Pointer.nativeValue(e.modBaseAddr), e.modBaseSize.longValue()));
                } while (out.size() < 4096 && k.Module32NextW(snap, e));
            }
        } finally {
            k.CloseHandle(snap);
        }
        return out;
    }

    /** The start addresses of this game's threads. */
    static List<Long> threadStarts() {
        List<Long> out = new ArrayList<>();
        Kernel32 k = Kernel32.INSTANCE;
        if (ntdll == null) ntdll = Native.load("ntdll", Ntdll.class);
        int pid = k.GetCurrentProcessId();
        WinNT.HANDLE snap = k.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new WinDef.DWORD(0));
        if (snap == null || WinNT.INVALID_HANDLE_VALUE.equals(snap)) return out;
        Memory mem = new Memory(Native.POINTER_SIZE);
        try {
            Tlhelp32.THREADENTRY32 t = new Tlhelp32.THREADENTRY32();
            if (k.Thread32First(snap, t)) {
                do {
                    if (t.th32OwnerProcessID != pid) continue;
                    WinNT.HANDLE h = k.OpenThread(THREAD_QUERY_INFORMATION, false, t.th32ThreadID);
                    if (h == null) continue;
                    try {
                        mem.clear();
                        if (ntdll.NtQueryInformationThread(h, THREAD_QUERY_SET_WIN32_START_ADDRESS, mem, Native.POINTER_SIZE, null) == 0) {
                            long start = Native.POINTER_SIZE == 8 ? mem.getLong(0) : mem.getInt(0) & 0xFFFFFFFFL;
                            if (start != 0) out.add(start);
                        }
                    } finally {
                        k.CloseHandle(h);
                    }
                } while (out.size() < 4096 && k.Thread32Next(snap, t));
            }
        } finally {
            k.CloseHandle(snap);
        }
        return out;
    }
}
