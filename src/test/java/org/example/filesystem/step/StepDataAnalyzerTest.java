package org.example.filesystem.step;

import org.example.filesystem.dto.step.StepAssemblyNode;
import org.example.filesystem.dto.step.StepEntitySnippet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepDataAnalyzerTest {

    @Test
    void analyze_extractsPartsAssemblyBboxAndMeasures() {
        String step = """
                ISO-10303-21;
                HEADER;
                FILE_DESCRIPTION(('test'),'2;1');
                FILE_NAME('a','t',('a'),('o'),'p','s','');
                FILE_SCHEMA(('AP214'));
                ENDSEC;
                DATA;
                #1=PRODUCT('\\X2\\96F6\\X0\\001','\\X2\\96F6\\X0\\件A','\\X2\\63CF\\X0\\述',(#100));
                #2=PRODUCT_DEFINITION_FORMATION('','',#1);
                #3=PRODUCT_DEFINITION('pd-a','pd-desc',#2,#200);
                #4=PRODUCT('P-ASM','装配','',(#101));
                #5=PRODUCT_DEFINITION_FORMATION('','',#4);
                #6=PRODUCT_DEFINITION('pd-asm','',#5,#200);
                #7=NEXT_ASSEMBLY_USAGE_OCCURRENCE('','', '',#6,#3,'R1');
                #10=CARTESIAN_POINT('',(1.,2.,3.));
                #11=CARTESIAN_POINT('',(-1.,5.,0.));
                #20=MEASURE_REPRESENTATION_ITEM('D',LENGTH_MEASURE(10.5),#300);
                #30=DIMENSIONAL_SIZE('\\X2\\5C3A5BF8\\X0\\',#31,#32);
                ENDSEC;
                END-ISO-10303-21;
                """;

        StepDataAnalyzer.Analysis analysis = StepDataAnalyzer.analyze(step, StepDataAnalyzer.Limits.defaults());

        assertThat(analysis.entitiesParsed()).isGreaterThan(0);
        assertThat(analysis.parts()).isNotEmpty();
        assertThat(analysis.parts().stream().anyMatch(p -> "P-ASM".equals(p.partNumber()))).isTrue();
        assertThat(analysis.parts().stream().anyMatch(p -> "零件A".equals(p.name()))).isTrue();

        assertThat(analysis.assemblyTree()).isNotNull();
        assertThat(analysis.assemblyTree().roots()).isNotEmpty();
        StepAssemblyNode root = analysis.assemblyTree().roots().getFirst();
        assertThat(root.part()).isNotNull();
        assertThat(root.part().partNumber()).isEqualTo("P-ASM");
        assertThat(root.children()).isNotEmpty();
        assertThat(root.children().getFirst().referenceDesignator()).isEqualTo("R1");

        assertThat(analysis.geometry()).isNotNull();
        assertThat(analysis.geometry().boundingBox()).isNotNull();
        assertThat(analysis.geometry().boundingBox().minX()).isEqualTo(-1.0);
        assertThat(analysis.geometry().boundingBox().maxY()).isEqualTo(5.0);

        assertThat(analysis.pmi()).isNotNull();
        assertThat(analysis.pmi().measures()).isNotEmpty();
        assertThat(analysis.pmi().measures().getFirst().value()).isEqualTo(10.5);

        assertThat(analysis.pmi().snippets()).isNotNull();
        assertThat(analysis.pmi().snippets().stream().map(StepEntitySnippet::text).anyMatch(t -> t.contains("尺寸"))).isTrue();
    }
}
