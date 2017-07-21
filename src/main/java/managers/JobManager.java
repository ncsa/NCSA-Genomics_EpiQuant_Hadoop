package managers;

import utilities.ConfSet;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.chain.ChainMapper;
import org.apache.hadoop.mapreduce.lib.chain.ChainReducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

public class JobManager {
    public static class LinearRegressionMapper extends Mapper<Object, Text, Text, Text>{

        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            BufferedReader buff = new BufferedReader(new StringReader(value.toString()));
            String line;
            String[] tokens;
            Configuration conf = context.getConfiguration();
            String mapKey = ConfSet.getY(conf);
            double[][] x;
            String model = ConfSet.getModel(conf);

            while ((line = buff.readLine()) != null) {
                tokens = line.split("\\t");
                OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();

                // Converts yString to double[], and x tokens to double[][].
                // Combines x and y data and adds them to regression object.
                x = ConfSet.combineX(tokens, new double[tokens.length - 1][1]); // Make sure to +1 index
                regression.newSampleData(ConfSet.convertY(mapKey), x);
                try {
                    calculateSignificance(regression, context, mapKey, tokens, model);
                } catch (Exception e) {
                    System.err.println("Invalid significance generated.");
                }
            }
            buff.close();
        }

        // Calculates significance of regressors.
        public static void calculateSignificance(OLSMultipleLinearRegression regression, Context context, String mapKey, String[] tokens, String model) throws Exception {
            final double[] beta = regression.estimateRegressionParameters();
            final double[] standardErrors = regression.estimateRegressionParametersStandardErrors();
            final int residualdf = regression.estimateResiduals().length - beta.length;

            final TDistribution tdistribution = new TDistribution(residualdf);

            double tstat = beta[beta.length - 1] / standardErrors[beta.length - 1];
            double pvalue = tdistribution.cumulativeProbability(-FastMath.abs(tstat)) * 2;
            if (pvalue < 0.05) {
                if (model.equals("")) {
                    context.write(new Text(mapKey), new Text(Double.toString(pvalue) + "\n" + ConfSet.getXNewString(tokens)));
                } else {
                    context.write(new Text(mapKey), new Text(Double.toString(pvalue) + "\t" + model + "\n" + ConfSet.getXNewString(tokens)));
                }
            }
        }
    }

    public static class MinimumSignificanceReducer extends Reducer<Text, Text, Text, Text> {
        private Text minX = new Text();

        public void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
            double tempMinP = 0.05;
            String tempMinX = "";
            String[] tokens;

            for (Text val: values) {
                tokens = val.toString().split("\\t");
                double significance = Double.parseDouble(tokens[0]);
                // If current is less do nothing.
                if (!(tempMinP < significance)) { 
                    // If current is greater, replace.
                    if (tempMinP > significance) {
                        tempMinP = significance;
                        tempMinX = val.toString();
                    } else { // If equal, randomly replace.
                        Random r = new Random();
                        if (r.nextBoolean()) {
                            tempMinP = significance;
                            tempMinX = val.toString();
                        }
                    }
                }
            }
            minX.set(tempMinX);
            context.write(key, minX);
        }
    }

    public static class ModelMapper extends Mapper<Text, Text, Text, Text>{
        public void map(Text key, Text value, Context context) throws IOException, InterruptedException {
            // buff.lines().collect(Collectors.joining());
            context.write(new Text("Hello"), new Text("World"));
        }
    }

    public Job run(String jobPath, String y, String model, int phenotype, int split) throws Exception {
        Configuration conf = new Configuration();
        conf.set("model", model);
        conf.set("y", y);
        Job job = Job.getInstance(conf, "job manager");

        Configuration chainMapperConf = new Configuration(false);
        ChainMapper.addMapper(job, LinearRegressionMapper.class, Object.class, Text.class, Text.class, Text.class, chainMapperConf);

        Configuration chainReducerConf = new Configuration(false);
        ChainReducer.setReducer(job, MinimumSignificanceReducer.class, Text.class, Text.class, Text.class, Text.class, chainReducerConf);
        ChainReducer.addMapper(job, ModelMapper.class, Text.class, Text.class, Text.class, Text.class, chainReducerConf);

        job.setJarByClass(JobManager.class);

        FileInputFormat.addInputPath(job, new Path(jobPath));
        FileOutputFormat.setOutputPath(job, new Path("Phenotype-" + phenotype + ".Split-" + split));
        
        job.waitForCompletion(true);
        return job;
    }
}