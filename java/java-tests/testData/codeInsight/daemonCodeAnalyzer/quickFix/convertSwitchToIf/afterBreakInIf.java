// "Replace 'switch' with 'if'" "true-preview"
class X {
  void m(String s, boolean r) {
      if ("a".equals(s)) {
          System.out.println("a");
          if (r) {
              return;
          }
      }
      System.out.println("d");
  }
}