package torch.model;

import torch.RecordComparator;
import torch.counter.Counter;
import torch.util.Util;

import java.util.Arrays;
import java.util.Random;

import cc.mallet.types.Dirichlet;

public class UnsupervisedBayes {
    public final static int BURN_IN = 500;
    public final static int N_ITER = 1000;

    public UnsupervisedBayes(Random rng, MixtureModelPrior prior, Counter counter,
                             int burnIn, int nIter) 
    {
        _prior = prior;
        _cmp = counter.recordComparator();
        _burnIn = burnIn;
        _nIter = nIter;

        // TODO there should be a test to ensure that the prior is compatible 
        // with the record comparator

        // Naming scheme:
        // _mWeights will store the posterior mean weights
        // _mWeightsStep will store a draw of weights during a single step
        
        int nClasses = _prior.nClasses();
        int nComparators = _cmp.nComparators();
        _mWeights = new double[nClasses][nComparators][];
        _mWeightsStep = new double[nClasses][nComparators][];

        for (int j = 0; j < nClasses; j++) {
            for (int k = 0; k < nComparators; k++) {
                _mWeights[j][k] = new double[_cmp.nLevels(k)];
                _mWeightsStep[j][k] = new double[_cmp.nLevels(k)];
            }
        }

        _classWeights = new double[nClasses];
        _classWeightsStep = new double[nClasses];

        mcmc(rng, counter);

        _model = new MixtureModel(_cmp, _mWeights, _prior.nMatchClasses());
    }


    public UnsupervisedBayes(Random rng, MixtureModelPrior prior, Counter counter) {
        this(rng, prior, counter, BURN_IN, N_ITER);
    }

    public UnsupervisedBayes(MixtureModelPrior prior, Counter counter, int burnIn, int nIter) {
        this(new Random(), prior, counter, burnIn, nIter);
    }

    public UnsupervisedBayes(MixtureModelPrior prior, Counter counter) {
        this(new Random(), prior, counter, BURN_IN, N_ITER);
    }

    public double[] classWeights() {
        return _classWeights;
    }

    public MixtureModel model() {
        return _model;
    }

    /**
     * Compute posterior mean parameters.
     */
    private void mcmc(Random rng, Counter counter) {
        int[] counts = counter.nonzeroCounts();
        int[][] patterns = counter.nonzeroPatterns();

        int[][] classAssign = new int[patterns.length][_prior.nClasses()];
        double[][] classWeightsCond = new double[patterns.length][_prior.nClasses()];

        // draw initial match weights from the prior
        drawWeights(rng, _prior.multinomialWeightParameter(), _prior.classWeightParameter());

        // draw initial class assignments
        drawClasses(rng, classAssign, classWeightsCond, counts, patterns);

        for (int n = 0; n < _burnIn; n++) {
            drawWeights(rng, counts, patterns, classAssign);
            drawClasses(rng, classAssign, classWeightsCond, counts, patterns);
        }

        for (int n = 1; n <= _nIter; n++) {
            drawWeights(rng, counts, patterns, classAssign);
            drawClasses(rng, classAssign, classWeightsCond, counts, patterns);
            updateMeans(n);
        }
    }

    private void drawClasses(Random rng, int[][] classAssign, double[][] classWeightsCond,
                             int[] counts, int[][] patterns)
    {
        int nClasses = _prior.nClasses();

        for (int i = 0; i < patterns.length; i++) {
            double classTotal = 0.0;

            for (int j = 0; j < _prior.nClasses(); j++) {
                classWeightsCond[i][j] = _classWeightsStep[j];

                for (int k = 0; k < _cmp.nComparators(); k++) {
                    classWeightsCond[i][j] *= _mWeightsStep[j][k][patterns[j][k]];
                }
                classTotal += classWeightsCond[i][j] ;
            }

            for (int j = 0; j < _prior.nClasses(); j++) {
                classWeightsCond[i][j] /= classTotal;
            }
        }

        for (int i = 0; i < patterns.length; i++) {
            Arrays.fill(classAssign[i], 0);

            for (int n = 0; n < counts[i]; n++) {
                int draw = Util.sampleMultinomial(rng, classWeightsCond[i]);
                classAssign[i][draw]++;
            }
        }
    }

    /**
     * Draw match weights from a Dirichlet distribution with the given parameter.
     */
    private void drawWeights(Random rng, double[][][] mParam, double[] classParam) 
    {

        double[] newClassWeights = new Dirichlet(classParam).nextDistribution();
        for (int j = 0; j < _prior.nClasses(); j++) {
            _classWeightsStep[j] = newClassWeights[j];

            for (int k = 0; k < _cmp.nComparators(); k++) {
                double[] newWeights = new Dirichlet(mParam[j][k]).nextDistribution();
                for (int x = 0; x < _cmp.nLevels(k); x++)
                    _mWeightsStep[j][k][x] = newWeights[x];
            }
        }
    }

    /**
     * Compute posterior parameters and draw new weights.
     */
    private void drawWeights(Random rng, int[] counts, int[][] patterns, int[][] classAssign) 
    {
        double[][][] mPrior = _prior.multinomialWeightParameter();
        double[] classPrior = _prior.classWeightParameter();

        double[] classParam = Arrays.copyOf(classPrior, classPrior.length);
        double[][][] mParam = new double[classPrior.length][_cmp.nComparators()][];

        for (int j = 0; j < _prior.nClasses(); j++) {
            for (int k = 0; k < _cmp.nComparators(); k++) {
                mParam[j][k] = Arrays.copyOf(mPrior[j][k], _cmp.nLevels(k));
            }
        }

        for (int i = 0; i < patterns.length; i++) {
            for (int j = 0; j < _prior.nClasses(); j++) {
                classParam[j] += classAssign[i][j];

                for (int k = 0; k < _cmp.nComparators(); k++) {
                    mParam[j][k][patterns[i][k]] += classAssign[i][j];
                }
            }
        }

        drawWeights(rng, mParam, classParam);
    }

    private void updateMeans(int nStep) {
        double a = (nStep - 1.0) / nStep;
        double b = 1.0 / nStep;

        for (int j = 0; j < _prior.nClasses(); j++) {
            _classWeights[j] = a * _classWeights[j] + b * _classWeightsStep[j];

            for (int k = 0; k < _cmp.nComparators(); k++) {
                for (int x = 0; x < _cmp.nLevels(k); x++) {
                    _mWeights[j][k][x] = a * _mWeights[j][k][x] + b * _mWeightsStep[j][k][x];
                }
            }
        }
    }

    private final int _burnIn, _nIter;
    private final MixtureModelPrior _prior;
    private final RecordComparator _cmp;
    private final double[][][] _mWeights, _mWeightsStep;
    private final double[] _classWeights, _classWeightsStep;
    private final MixtureModel _model;
}
