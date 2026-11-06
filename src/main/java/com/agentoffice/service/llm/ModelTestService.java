package com.agentoffice.service.llm;

import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmResponse;
import com.agentoffice.domain.usage.BudgetExceededException;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.LlmCallException;
import com.agentoffice.dto.llm.ModelTestResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Gọi thử một model bằng một câu ngắn, qua MeteredLlmClient (nên tốn token thật, có ghi usage purpose=MODEL_TEST và bị ngân sách chặn). */
@Service
public class ModelTestService {
    private static final Logger log = LoggerFactory.getLogger(ModelTestService.class);
    private static final CallContext CONTEXT = new CallContext(null, null, null, null, null, CallContext.Purpose.MODEL_TEST, null);

    private final MeteredLlmClient llm;

    public ModelTestService(MeteredLlmClient llm) { this.llm = llm; }

    /** Hết ngân sách thì ném BudgetExceededException (422) chứ không báo "model hỏng"; mọi lỗi gọi khác thành ok=false. */
    public ModelTestResponse test(String model) {
        long start = System.nanoTime();
        try {
            LlmResponse r = llm.complete(new LlmRequest(model, List.of(new LlmRequest.Msg("user", "Trả lời đúng một từ: pong")), 256, CONTEXT));
            return new ModelTestResponse(model, true, ms(start), r.inputTokens(), r.outputTokens(), null);
        } catch (BudgetExceededException e) {
            throw e;
        } catch (LlmCallException e) {
            return new ModelTestResponse(model, false, ms(start), 0, 0, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Test model {} lỗi ngoài dự kiến", model, e);
            return new ModelTestResponse(model, false, ms(start), 0, 0, "Lỗi không phân loại: " + e.getClass().getSimpleName());
        }
    }

    private static long ms(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
