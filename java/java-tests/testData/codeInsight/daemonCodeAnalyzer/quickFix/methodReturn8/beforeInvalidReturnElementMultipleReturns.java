// "Make 'm' return 'java.util.AbstractList<java.lang.Object>' or predecessor" "true-preview"
import java.util.*;

class Test {

  void m(boolean b) {
    if (b) <caret>return new ArrayList<>();
    return new LinkedList<>();
  }

}