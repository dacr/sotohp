package fr.janalyse.sotohp.processor;

// Whole-image embedding translator. Mirrors FeatureExtraction.java (the face variant),
// but with standard ImageNet preprocessing and no softmax on the output: the raw
// pre-softmax vector of a traced classification model (e.g. djl://ai.djl.pytorch/resnet
// -> traced_resnet18, 1000-d) is L2-normalised and used directly as a visual descriptor
// for similar-photo search and clustering.

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

public final class ImageEmbeddingExtraction {

    private ImageEmbeddingExtraction() {}

    public static final class ImageEmbeddingTranslator implements Translator<Image, float[]> {

        // ImageNet normalisation constants (torchvision convention).
        private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
        private static final float[] STD = {0.229f, 0.224f, 0.225f};

        public ImageEmbeddingTranslator() {}

        @Override
        public NDList processInput(TranslatorContext ctx, Image input) {
            NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
            Pipeline pipeline = new Pipeline();
            pipeline
                    .add(new Resize(224, 224))
                    .add(new ToTensor())
                    .add(new Normalize(MEAN, STD));
            return pipeline.transform(new NDList(array));
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            // toFloatArray() flattens whatever shape the model emits ((1,1000), (1000,), ...).
            float[] raw = list.singletonOrThrow().toFloatArray();
            double norm = 0d;
            for (float v : raw) {
                norm += (double) v * (double) v;
            }
            norm = Math.sqrt(norm);
            if (norm <= 0d || Double.isNaN(norm)) {
                return raw;
            }
            float[] normalized = new float[raw.length];
            for (int i = 0; i < raw.length; i++) {
                normalized[i] = (float) (raw[i] / norm);
            }
            return normalized;
        }
    }
}
