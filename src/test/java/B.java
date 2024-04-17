public class B {
    private static B b = null;

    public static B getB() {
        if (b == null) {
            b =new B();
        }
        return b;
    }
}
