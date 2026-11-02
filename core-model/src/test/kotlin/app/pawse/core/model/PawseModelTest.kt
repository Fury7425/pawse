package app.pawse.core.model

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test

class PawseModelTest {

    @Test
    fun `thinking, template tokens and markdown never reach the number check`() {
        clean("<think>7 + 8 is 15</think>You slept 7 h 24.<|im_end|>") shouldBe "You slept 7 h 24."
        clean("**HRV** was 58 ms.\n- Aim for bed by 23:00.") shouldBe "HRV was 58 ms.\nAim for bed by 23:00."
        clean("<think>never closed, 99 ms") shouldBe ""
    }

    @Test
    fun `answers with surviving numbers are checked, others are general tips`() {
        label("HRV was 58 ms.").kind shouldBe Answer.Kind.CHECKED
        label("A dark, cool room helps.").kind shouldBe Answer.Kind.GENERAL
        label(null).kind shouldBe Answer.Kind.UNCHECKED
    }

    @Test
    fun `the prompt turns thinking off before the reply`() {
        lfmPrompt("s", "u").endsWith("<|im_end|>\n<|im_start|>assistant\n<think></think>").shouldBeTrue()
    }
}
