import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import java.io.File;

public final class MergeInputs {
    public static void main(String[] args) throws Exception {
        ApkBundle bundle = new ApkBundle();
        bundle.setManifestMerger(null);
        bundle.loadApkDirectory(new File(args[0]), false);
        ApkModule merged = bundle.mergeModules(true);
        merged.refreshTable();
        merged.refreshManifest();
        merged.writeApk(new File(args[1]));
        merged.close();
        bundle.close();
    }
}
