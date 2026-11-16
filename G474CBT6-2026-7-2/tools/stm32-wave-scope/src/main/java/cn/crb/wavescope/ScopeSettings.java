package cn.crb.wavescope;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import java.util.ArrayList;
import java.util.List;

@State(name = "STM32WaveScope", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ScopeSettings implements PersistentStateComponent<ScopeSettings.StateData> {
    public static final class SavedWatch {
        public String expression = "";
        public String type = "AUTO";
        public boolean enabled = true;
        public boolean expanded = true;
        public List<Integer> dimensions = new ArrayList<>();
        public List<String> members = new ArrayList<>();
        public boolean writable = true;
        public List<SavedWatch> children = new ArrayList<>();
        public SavedWatch() { }
        public SavedWatch(String expression) { this.expression = expression; }
    }
    public static final class StateData {
        public String elf = "";
        public String gdb = "";
        public String host = "127.0.0.1";
        public int port = 6666;
        public int periodMs = 50;
        public boolean fastSampling = false;
        public int samplingProfileVersion = 0;
        public int windowSeconds = 15;
        public boolean lanes = true;
        public List<SavedWatch> watches = new ArrayList<>();
    }
    private StateData state = new StateData();
    @Override public StateData getState() { return state; }
    @Override public void loadState(StateData state) { this.state = state; }
}
