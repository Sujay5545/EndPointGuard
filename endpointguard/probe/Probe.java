package probe;
import com.endpointguard.common.config.AppProperties;
import com.endpointguard.review.dto.ReviewDecision;
import com.endpointguard.review.dto.ReviewRequest;
import com.endpointguard.review.provider.AnthropicLlmReviewProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
public class Probe {
  public static void main(String[] args) throws Exception {
    AppProperties p = new AppProperties();
    p.getLlm().getAnthropic().setApiKey("test-key");
    RestTemplate rt = mock(RestTemplate.class);

    when(rt.postForEntity(anyString(), any(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"decision\\\":\\\"MEDIUM\\\",\\\"score\\\":72.5,\\\"summary\\\":\\\"Review complete\\\",\\\"findings\\\":[\\\"Check the payment validation\\\"]}\"}] }"));
    ReviewDecision d1 = new AnthropicLlmReviewProvider(rt, p, new ObjectMapper()).review(new ReviewRequest("acme/payments-api", "Protect the payment flow", "diff", "Affected endpoints", 72.5));
    if (!"MEDIUM".equals(d1.decision()) || d1.fallback()) throw new AssertionError("normal json failed: " + d1);

    when(rt.postForEntity(anyString(), any(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"id\":\"msg_2\",\"content\":[{\"type\":\"text\",\"text\":\"```json\\n{\\\"decision\\\":\\\"HIGH\\\",\\\"score\\\":92.0,\\\"summary\\\":\\\"Looks safe\\\",\\\"findings\\\":[\\\"No critical issue found\\\"]}\\n```\"}] }"));
    ReviewDecision d2 = new AnthropicLlmReviewProvider(rt, p, new ObjectMapper()).review(new ReviewRequest("acme/payments-api", "Add safer validation", "diff", "Affected endpoints", 81.0));
    if (!"HIGH".equals(d2.decision()) || d2.fallback()) throw new AssertionError("fenced json failed: " + d2);

    when(rt.postForEntity(anyString(), any(), eq(String.class))).thenReturn(ResponseEntity.ok("{\"id\":\"msg_3\",\"content\":[{\"type\":\"text\",\"text\":\"This is not valid JSON.\"}] }"));
    ReviewDecision d3 = new AnthropicLlmReviewProvider(rt, p, new ObjectMapper()).review(new ReviewRequest("acme/payments-api", "Add safer validation", "diff", "Affected endpoints", 81.0));
    if (!d3.fallback()) throw new AssertionError("invalid json should fallback: " + d3);

    System.out.println("Anthropic provider verification OK");
  }
}
