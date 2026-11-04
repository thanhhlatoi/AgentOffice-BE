package com.agentoffice.domain.usage;

import java.math.BigInteger;

/**
 * Chi phí bằng số nguyên micro-USD:
 *   cost = ceil(inputTokens * inputMicroPerMTok / 1_000_000) + ceil(outputTokens * outputMicroPerMTok / 1_000_000)
 * - Làm tròn LÊN từng vế (chưa tính thì không bao giờ báo thấp hơn thực tế).
 * - Tích tokens x giá có thể vượt long (1e13 token x 1e8 = 1e21) nên nhân bằng BigInteger; chỉ khi KẾT QUẢ không vừa long mới lỗi
 *   (ArithmeticException), không bao giờ trả số sai âm thầm.
 * - Đầu vào âm => IllegalArgumentException.
 */
public class CostCalculator {
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);

    public long costMicroUsd(long inputTokens, long outputTokens, long inputMicroPerMTok, long outputMicroPerMTok) {
        if (inputTokens < 0 || outputTokens < 0 || inputMicroPerMTok < 0 || outputMicroPerMTok < 0)
            throw new IllegalArgumentException("token và giá không được âm");
        return Math.addExact(ceilPerMillion(inputTokens, inputMicroPerMTok), ceilPerMillion(outputTokens, outputMicroPerMTok));
    }

    private static long ceilPerMillion(long tokens, long microPerMTok) {
        BigInteger[] qr = BigInteger.valueOf(tokens).multiply(BigInteger.valueOf(microPerMTok)).divideAndRemainder(MILLION);
        return (qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE)).longValueExact();
    }
}
