package ee.carlrobert.codegpt.embeddings.splitter;

import java.util.List;

public interface Splitter {

  List<String> split(String fileName, String content);
}
