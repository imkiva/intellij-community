// "Replace 'switch' with 'if'" "true-preview"
abstract class Test {
  abstract Object getObject();

  void foo() {
      if (RuntimeException.class.equals(getObject().getClass())) {
          System.out.println("RuntimeException");
      } else {
          System.out.println("Other");
      }
  }
}