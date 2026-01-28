package org.example.filesystem.step;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepModelInfoParserTest {

    @Test
    void decodeStepEscapes_decodesX2Unicode() {
        assertThat(StepModelInfoParser.decodeStepEscapes("\\X2\\4E2D6587\\X0\\"))
                .isEqualTo("中文");
    }

    @Test
    void parse_extractsHeaderFieldsAndProductNames() {
        String step = """
                ISO-10303-21;
                HEADER;
                FILE_DESCRIPTION(('\\X2\\4E2D65876A21578B\\X0\\'),'2;1');
                FILE_NAME('\\X2\\4E2D65876A21578B\\X0\\.stp','2026-01-21T00:00:00',('\\X2\\4F5C8005\\X0\\'),('组织'),'预处理器','系统','授权');
                FILE_SCHEMA(('AP214'));
                ENDSEC;
                DATA;
                #10=PRODUCT('\\X2\\4EA754C1\\X0\\A','',#20,#30);
                ENDSEC;
                END-ISO-10303-21;
                """;

        StepModelInfoParser.StepModelInfo info = StepModelInfoParser.parse(step);

        assertThat(info.fileDescriptions()).containsExactly("中文模型");
        assertThat(info.implementationLevel()).isEqualTo("2;1");
        assertThat(info.fileName()).isEqualTo("中文模型.stp");
        assertThat(info.timeStamp()).isEqualTo("2026-01-21T00:00:00");
        assertThat(info.authors()).containsExactly("作者");
        assertThat(info.organizations()).containsExactly("组织");
        assertThat(info.preprocessorVersion()).isEqualTo("预处理器");
        assertThat(info.originatingSystem()).isEqualTo("系统");
        assertThat(info.authorization()).isEqualTo("授权");
        assertThat(info.schemas()).containsExactly("AP214");
        assertThat(info.productNames()).containsExactly("产品A");
        assertThat(info.warnings()).isNull();
    }

    @Test
    void parse_handlesEscapedSingleQuoteInString() {
        String step = """
                ISO-10303-21;
                HEADER;
                FILE_DESCRIPTION(('a'),'2;1');
                FILE_NAME('O''Reilly','t',('a'),('o'),'p','s','');
                FILE_SCHEMA(('AP214'));
                ENDSEC;
                DATA;
                ENDSEC;
                END-ISO-10303-21;
                """;

        StepModelInfoParser.StepModelInfo info = StepModelInfoParser.parse(step);
        assertThat(info.fileName()).isEqualTo("O'Reilly");
    }
}
