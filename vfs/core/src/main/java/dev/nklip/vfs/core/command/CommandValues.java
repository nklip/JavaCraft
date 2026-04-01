package dev.nklip.vfs.core.command;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Lipatov Nikita
 */
public class CommandValues {
    @Setter
    @Getter
    private String source;
    @Setter
    @Getter
    private String command;
    @Setter
    @Getter
    private List<String> keys   = new ArrayList<>();
    @Setter
    @Getter
    private List<String> params = new ArrayList<>();

    private int keyPointer = 0;
    private int paramPointer = 0;

    public String getNextKey() {
        return (keys.size() > keyPointer)
                ? keys.get(keyPointer++)
                : null;
    }

    public String getNextParam() {
        return (params.size() > paramPointer)
                ? params.get(paramPointer++)
                : null;
    }


}
