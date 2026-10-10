package com.riftos.bootstrap.proof;

import android.app.Application;
import android.os.Process;
import android.util.AtomicFile;
import com.riftos.app.RiftBootstrapEntry;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * External-only proof fixture. This file is deliberately outside
 * android/app/src/main; never embed it inside the production RiftOS APK.
 * Build as a separate DEX against the stable RiftBootstrapEntry ABI.
 */
public final class ProbeV1 implements RiftBootstrapEntry {
    @Override
    public void start(Application application) {
        File root = new File(application.getFilesDir(), "bootstrap-components");
        if (!root.isDirectory()) throw new IllegalStateException("Missing staged module directory");
        AtomicFile marker = new AtomicFile(new File(root, "probe-proof.json"));
        FileOutputStream output = null;
        try {
            JSONObject result = new JSONObject()
                .put("schema", "riftos.bootstrap-probe-proof/1")
                .put("revision", "probe-v1")
                .put("pid", Process.myPid())
                .put("process", android.os.Build.VERSION.SDK_INT >= 28
                    ? Application.getProcessName() : "pre-api28")
                .put("atMs", System.currentTimeMillis());
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            output = marker.startWrite();
            output.write(bytes);
            marker.finishWrite(output);
        } catch (Exception error) {
            if (output != null) marker.failWrite(output);
            throw new IllegalStateException("External proof module did not complete", error);
        }
    }
}
