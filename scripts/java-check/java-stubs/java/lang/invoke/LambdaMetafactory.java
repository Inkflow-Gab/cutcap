package java.lang.invoke;

/**
 * Stub for the offline Java check only. See scripts/java-check/capacitor/README.md.
 *
 * The android-36 platform jar that the check compiles against predates the JVM's LambdaMetafactory
 * (it is absent from its java.* tree), so javac/ECJ cannot emit an invokedynamic for a lambda or a
 * method reference. Every Android build resolves this from the boot classpath; the stub exists so
 * the check sees the same shape. Runtime never loads it: it is not part of the APK.
 */
public final class LambdaMetafactory {
    private LambdaMetafactory() {}

    public static CallSite metafactory(
            MethodHandles.Lookup caller,
            String interfaceMethodName,
            MethodType factoryType,
            MethodType interfaceMethodType,
            MethodHandle implementation,
            MethodType dynamicMethodType) throws LambdaConversionException {
        return null;
    }

    public static CallSite altMetafactory(
            MethodHandles.Lookup caller,
            String interfaceMethodName,
            MethodType factoryType,
            Object... args) throws LambdaConversionException {
        return null;
    }
}
