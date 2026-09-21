package mergeroom;

import mergeroom.test.PlatformExprTest;
import mergeroom.test.ParserPrinterTest;
import mergeroom.test.MergeEngineTest;
import mergeroom.test.SessionStoreTest;

public final class TestRunner {

    public static void main(String[] args) {
        mergeroom.test.TestFramework t = new mergeroom.test.TestFramework();
        PlatformExprTest.register(t);
        ParserPrinterTest.register(t);
        MergeEngineTest.register(t);
        SessionStoreTest.register(t);
        System.exit(t.run());
    }
}
