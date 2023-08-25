package ee.carlrobert.codegpt.completions;

import static ee.carlrobert.codegpt.completions.CompletionRequestProvider.COMPLETION_SYSTEM_PROMPT;
import static ee.carlrobert.openai.util.JSONUtil.e;
import static ee.carlrobert.openai.util.JSONUtil.jsonArray;
import static ee.carlrobert.openai.util.JSONUtil.jsonMap;
import static ee.carlrobert.openai.util.JSONUtil.jsonMapResponse;
import static org.apache.http.HttpHeaders.AUTHORIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.ConversationService;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.credentials.OpenAICredentialsManager;
import ee.carlrobert.codegpt.settings.SettingsState;
import ee.carlrobert.codegpt.settings.configuration.ConfigurationState;
import ee.carlrobert.openai.client.ClientCode;
import ee.carlrobert.openai.client.completion.chat.ChatCompletionModel;
import ee.carlrobert.openai.client.completion.text.TextCompletionModel;
import ee.carlrobert.openai.http.LocalCallbackServer;
import ee.carlrobert.openai.http.ResponseEntity;
import ee.carlrobert.openai.http.exchange.BasicHttpExchange;
import ee.carlrobert.openai.http.expectation.BasicExpectation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CompletionRequestProviderTest extends BasePlatformTestCase {

  private LocalCallbackServer server;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    server = new LocalCallbackServer(8000);
    SettingsState.getInstance().openAIBaseHost = "http://127.0.0.1:8000";
    OpenAICredentialsManager.getInstance().setApiKey("TEST_API_KEY");
    ConfigurationState.getInstance().systemPrompt = "";
  }

  @Override
  protected void tearDown() throws Exception {
    server.stop();
    super.tearDown();
  }

  public void testTextCompletionRequestWithSystemPromptOverride() {
    ConfigurationState.getInstance().systemPrompt = "TEST_SYSTEM_PROMPT";
    var conversation = createConversation(ClientCode.TEXT_COMPLETION);
    var firstMsg = new Message("TEST_PROMPT", "TEST_RESPONSE");
    conversation.addMessage(firstMsg);
    conversation.addMessage(new Message("TEST_PROMPT_2", "TEST_RESPONSE_2"));

    var request = new CompletionRequestProvider(getProject(), conversation)
        .buildTextCompletionRequest(TextCompletionModel.DAVINCI.getCode(), new Message("TEST_TEXT_COMPLETION_PROMPT"), false);

    assertThat(request.getPrompt())
        .isEqualTo("TEST_SYSTEM_PROMPT\n"
            + "Human: TEST_PROMPT\n"
            + "AI: TEST_RESPONSE\n"
            + "Human: TEST_PROMPT_2\n"
            + "AI: TEST_RESPONSE_2\n"
            + "Human: TEST_TEXT_COMPLETION_PROMPT\n"
            + "AI: \n");
  }

  public void testTextCompletionRequestWithoutSystemPromptOverride() {
    var conversation = createConversation(ClientCode.TEXT_COMPLETION);
    var firstMsg = new Message("TEST_PROMPT", "TEST_RESPONSE");
    conversation.addMessage(firstMsg);
    conversation.addMessage(new Message("TEST_PROMPT_2", "TEST_RESPONSE_2"));

    var request = new CompletionRequestProvider(getProject(), conversation)
        .buildTextCompletionRequest(TextCompletionModel.DAVINCI.getCode(), new Message("TEST_TEXT_COMPLETION_PROMPT"), false);

    assertThat(request.getPrompt())
        .isEqualTo("You are ChatGPT, a large language model trained by OpenAI.\n"
            + "Answer in a markdown language, code blocks should contain language whenever possible.\n"
            + "Human: TEST_PROMPT\n"
            + "AI: TEST_RESPONSE\n"
            + "Human: TEST_PROMPT_2\n"
            + "AI: TEST_RESPONSE_2\n"
            + "Human: TEST_TEXT_COMPLETION_PROMPT\n"
            + "AI: \n");
  }

  public void testChatCompletionRequestWithSystemPromptOverride() {
    ConfigurationState.getInstance().systemPrompt = "TEST_SYSTEM_PROMPT";
    var conversation = ConversationService.getInstance().startConversation();
    var firstMessage = createDummyMessage(500);
    var secondMessage = createDummyMessage(250);
    conversation.addMessage(firstMessage);
    conversation.addMessage(secondMessage);

    var request = new CompletionRequestProvider(getProject(), conversation)
        .buildChatCompletionRequest(ChatCompletionModel.GPT_3_5.getCode(), new Message("TEST_CHAT_COMPLETION_PROMPT"), false, false);

    assertThat(request.getMessages())
        .extracting("role", "content")
        .containsExactly(
            tuple("system", "TEST_SYSTEM_PROMPT"),
            tuple("user", "TEST_PROMPT"),
            tuple("assistant", firstMessage.getResponse()),
            tuple("user", "TEST_PROMPT"),
            tuple("assistant", secondMessage.getResponse()),
            tuple("user", "TEST_CHAT_COMPLETION_PROMPT"));
  }

  public void testChatCompletionRequestWithoutSystemPromptOverride() {
    var conversation = ConversationService.getInstance().startConversation();
    var firstMessage = createDummyMessage(500);
    var secondMessage = createDummyMessage(250);
    conversation.addMessage(firstMessage);
    conversation.addMessage(secondMessage);

    var request = new CompletionRequestProvider(getProject(), conversation)
        .buildChatCompletionRequest(ChatCompletionModel.GPT_3_5.getCode(), new Message("TEST_CHAT_COMPLETION_PROMPT"), false);

    assertThat(request.getMessages())
        .extracting("role", "content")
        .containsExactly(
            tuple("system", COMPLETION_SYSTEM_PROMPT),
            tuple("user", "TEST_PROMPT"),
            tuple("assistant", firstMessage.getResponse()),
            tuple("user", "TEST_PROMPT"),
            tuple("assistant", secondMessage.getResponse()),
            tuple("user", "TEST_CHAT_COMPLETION_PROMPT"));
  }

  public void testChatCompletionRequestRetry() {
    var conversation = ConversationService.getInstance().startConversation();
    var firstMessage = createDummyMessage("FIRST_TEST_PROMPT", 500);
    var secondMessage = createDummyMessage("SECOND_TEST_PROMPT", 250);
    conversation.addMessage(firstMessage);
    conversation.addMessage(secondMessage);

    var request = new CompletionRequestProvider(getProject(), conversation)
        .buildChatCompletionRequest(ChatCompletionModel.GPT_3_5.getCode(), secondMessage, true);

    assertThat(request.getMessages())
        .extracting("role", "content")
        .containsExactly(
            tuple("system", COMPLETION_SYSTEM_PROMPT),
            tuple("user", "FIRST_TEST_PROMPT"),
            tuple("assistant", firstMessage.getResponse()),
            tuple("user", "SECOND_TEST_PROMPT"));
  }

  public void testReducedChatCompletionRequest() {
    var conversation = ConversationService.getInstance().startConversation();
    conversation.addMessage(createDummyMessage(50));
    conversation.addMessage(createDummyMessage(100));
    conversation.addMessage(createDummyMessage(150));
    conversation.addMessage(createDummyMessage(1000));
    var remainingMessage = createDummyMessage(2000);
    conversation.addMessage(remainingMessage);
    conversation.discardTokenLimits();

    var request = new CompletionRequestProvider(getProject(), conversation)
        .buildChatCompletionRequest(ChatCompletionModel.GPT_3_5.getCode(), new Message("TEST_CHAT_COMPLETION_PROMPT"), false);

    assertThat(request.getMessages())
        .extracting("role", "content")
        .containsExactly(
            tuple("system", COMPLETION_SYSTEM_PROMPT),
            tuple("user", "TEST_PROMPT"),
            tuple("assistant", remainingMessage.getResponse()),
            tuple("user", "TEST_CHAT_COMPLETION_PROMPT"));
  }

  public void testTotalUsageExceededException() {
    var conversation = ConversationService.getInstance().startConversation();
    conversation.addMessage(createDummyMessage(1500));
    conversation.addMessage(createDummyMessage(1500));
    conversation.addMessage(createDummyMessage(1500));

    assertThrows(TotalUsageExceededException.class,
        () -> new CompletionRequestProvider(getProject(), conversation)
            .buildChatCompletionRequest(ChatCompletionModel.GPT_3_5.getCode(), createDummyMessage(100), false));
  }

  public void testContextualSearch() {
    var conversation = ConversationService.getInstance().startConversation();
    var settings = SettingsState.getInstance();
    settings.isTextCompletionOptionSelected = false;
    settings.isChatCompletionOptionSelected = true;
    settings.useOpenAIService = true;
    settings.useAzureService = false;
    expectRequest("/v1/chat/completions", request -> {
      assertThat(request.getMethod()).isEqualTo("POST");
      assertThat(request.getHeaders().get(AUTHORIZATION).get(0)).isEqualTo("Bearer TEST_API_KEY");
      assertThat(request.getBody())
          .extracting("model", "messages")
          .containsExactly("gpt-4",
              List.of(Map.of(
                  "role",
                  "user",
                  "content",
                  "You are Text Generator, a helpful expert of generating natural language into semantically comparable search query.\n" +
                      "\n" +
                      "Text: List all the dependencies that the project uses\n" +
                      "AI: project dependencies, development dependencies, versions, libraries, frameworks, packages\n" +
                      "\n" +
                      "Text: Are there any scheduled tasks or background jobs running in our codebase, and if so, what are they responsible for?\n" +
                      "AI: scheduled tasks, background jobs, cron jobs, task schedules, codebase tasks\n" +
                      "\n" +
                      "Text: TEST_CHAT_COMPLETION_PROMPT\n" +
                      "AI:")));

      return new ResponseEntity(200,
          jsonMapResponse("choices", jsonArray(jsonMap("message", jsonMap(e("role", "assistant"), e("content", "TEST_CHAT_COMPLETION_RESPONSE"))))));
    });
    expectRequest("/v1/embeddings", request -> {
      var headers = request.getHeaders();
      assertThat(headers.get("Authorization").get(0)).isEqualTo("Bearer TEST_API_KEY");
      assertThat(request.getBody())
          .extracting("model", "input")
          .containsExactly("text-embedding-ada-002", List.of("TEST_CHAT_COMPLETION_RESPONSE"));
      return new ResponseEntity(200, jsonMapResponse("data", jsonArray(jsonMap("embedding", List.of(-0.00692, -0.0053, -4.5471, -0.0240)))));
    });

    var request = new CompletionRequestProvider(getProject(), conversation)
        .buildChatCompletionRequest(ChatCompletionModel.GPT_3_5.getCode(), new Message("TEST_CHAT_COMPLETION_PROMPT"), false, true);

    assertThat(request.getModel()).isEqualTo("gpt-3.5-turbo");
    assertThat(request.getMessages().size()).isEqualTo(1);
    assertThat(request.getMessages().get(0))
        .extracting("role", "content")
        .containsExactly("user", "Use the following pieces of context to answer the question at the end.\n" +
            "If you don't know the answer, just say that you don't know, don't try to make up an answer.\n" +
            "\n" +
            "Context:\n" +
            "\n" +
            "TEST_CONTEXT\n" +
            "\n" +
            "Question: TEST_CHAT_COMPLETION_PROMPT\n" +
            "\n" +
            "Helpful answer in Markdown format:");
  }

  private Conversation createConversation(ClientCode clientCode) {
    var settings = SettingsState.getInstance();
    var conversation = new Conversation();
    conversation.setId(UUID.randomUUID());
    conversation.setClientCode(clientCode);
    conversation.setModel(settings.isChatCompletionOptionSelected ?
        settings.getChatCompletionModel() :
        settings.getTextCompletionModel());
    conversation.setCreatedOn(LocalDateTime.now());
    conversation.setUpdatedOn(LocalDateTime.now());
    return conversation;
  }

  private Message createDummyMessage(int tokenSize) {
    return createDummyMessage("TEST_PROMPT", tokenSize);
  }

  private Message createDummyMessage(String prompt, int tokenSize) {
    var message = new Message(prompt);
    // 'zz' = 1 token, prompt = 6 tokens, 7 tokens per message (GPT-3),
    message.setResponse("zz".repeat((tokenSize) - 6 - 7));
    return message;
  }

  private void expectRequest(String path, BasicHttpExchange exchange) {
    server.addExpectation(new BasicExpectation(path, exchange));
  }
}
