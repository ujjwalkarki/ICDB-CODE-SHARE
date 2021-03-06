package main;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Charsets;
import crypto.AlgorithmType;
import crypto.ECParams;
import crypto.signer.ECSigner;
import io.Format;
import io.source.DataSource;
import main.args.*;
import main.args.config.UserConfig;
import main.args.option.Granularity;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.base.Stopwatch;

import io.DBConnection;
import io.DBConverter;
import io.SchemaConverter;
import main.args.config.ConfigArgs;
import org.bouncycastle.crypto.ec.*;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.tls.ECPointFormat;
import org.bouncycastle.math.ec.*;
import org.bouncycastle.util.encoders.Hex;
import parse.ICDBQuery;
import stats.RunStatistics;
import stats.Statistics;
import stats.StatisticsMetadata;
import verify.QueryVerifier;
import verify.serial.AbstractIcrl;
import verify.serial.Icrl;

import static org.bouncycastle.crypto.tls.TlsECCUtils.isCompressionPreferred;
import static org.junit.Assert.fail;

/**
 * <p>
 * A tool for performing ICDB-related tasks.
 * This class includes method to convert database, convert the query, execute the query, run the benchmark
 * Based on the type of commandline argument, the particular method is called.
 * This is the main class for the project
 * </p>
 *
 */
public class ICDBTool {

    // The time unit for all timed log statements
    public static final TimeUnit TIME_UNIT = TimeUnit.MILLISECONDS;

	private static final Logger logger = LogManager.getLogger();

    protected AbstractIcrl icrl = Icrl.Companion.getIcrl();

    public  static Map<Integer, Long> ICRLmap;


    public static void main(String[] args) throws FileNotFoundException {



//        try {
//            performTest();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//      System.exit(0);
		Stopwatch totalTime = Stopwatch.createStarted();

		// Parse the command-line arguments
		CommandLineArgs cmd = new CommandLineArgs(args);
		ConfigArgs configArgs = cmd.getConfig();
        UserConfig dbConfig = UserConfig.init(configArgs);

        Icrl.Companion.debug(!dbConfig.validateIcrl);

        //load the ICRL hash MAP
        ///update ICRLHASH for newly updated ICRL file (NOTE:TIME is not reported for background update of ICRLMAP)


        ICRLmap=new HashMap<>();
        try {
            File f = new File("ICRL.txt");
            if(!f.exists())
                f.createNewFile();

            try(Stream<String> lines = Files.lines(Paths.get("ICRL.txt"))){
                try {
                    Files.lines(Paths.get("ICRL.txt")).forEach(
                            line -> ICRLmap.putIfAbsent(line.hashCode(), Long.valueOf(line))
                    );
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

		DBConnection.configure(dbConfig);

		// Execute a command
		if (cmd.isCommand(CommandLineArgs.CONVERT_DB)) {
			convertDB(cmd, dbConfig);
		} else if (cmd.isCommand(CommandLineArgs.CONVERT_QUERY)) {
			convertQuery(cmd, dbConfig);
		} else if (cmd.isCommand(CommandLineArgs.EXECUTE_QUERY)) {
			executeQuery(cmd, dbConfig);
		} else if (cmd.isCommand(CommandLineArgs.EXECUTE_QUERY_SINGLE_BENCHMARK)) {
            executeQuerybenchmark(cmd, dbConfig);
        }
		else if (cmd.isCommand(CommandLineArgs.BENCHMARK)) {
            benchmark(cmd, dbConfig);
		} else { // TODO: add revoke serial command
			cmd.jCommander.usage();
			System.exit(0);
		}



        logger.info("");
		logger.info("Total time elapsed: {}", totalTime.elapsed(ICDBTool.TIME_UNIT));
	}


	/**
	 * Converts the specified DB to an ICDB
	 */
	private static void convertDB(CommandLineArgs cmd, UserConfig dbConfig) {
	    //delete the ICRL FILE if exists for the new database!

        try{

            File file = new File("ICRL.txt");

            if(file.delete()){
                System.out.println(file.getName() + " is deleted!");
            }else{
                System.out.println("Delete operation is failed.");
            }

        }catch(Exception e){

            e.printStackTrace();

        }


		final ConvertDBCommand convertConfig = cmd.convertDBCommand;

		// Duplicate the DB, and add additional columns
		DBConnection db = DBConnection.connect(dbConfig.schema, dbConfig);
		SchemaConverter.convertSchema(db, dbConfig, convertConfig);

		// Connect to the newly created DB
		DBConnection icdb = DBConnection.connect(dbConfig.icdbSchema, dbConfig);
		DBConverter dbConverter = new DBConverter(db, icdb, dbConfig, convertConfig);

		// Export, convert, and load all data
		dbConverter.convertAll();
	}

	/**
	 * Converts the Query to an ICDB Query
	 */
	private static void convertQuery(CommandLineArgs cmd, UserConfig dbConfig) {
        final ConvertQueryCommand convertQueryCmd = cmd.convertQueryCommand;
        final String icdbSchema = dbConfig.icdbSchema;

        DBConnection icdb = DBConnection.connect(icdbSchema, dbConfig);

        convertQueryCmd.queries.forEach(query -> {
            ICDBQuery icdbQuery = dbConfig.granularity.getQuery(query, icdb, dbConfig.codeGen, new RunStatistics());

            logger.info("Verify query:");
            logger.info(Format.limit(icdbQuery.getVerifyQuery()));
            System.out.println(icdbQuery.getVerifyQuery());

            logger.info("Converted query:");
            logger.info(Format.limit(icdbQuery.getConvertedQuery()));

            logger.info("Aggregate Verification query:");
            logger.info(Format.limit(icdbQuery.getAggregateQuery()));
        });
    }

    /**
     * Executes a query
     */
    private static void executeQuery(CommandLineArgs cmd, UserConfig dbConfig) {
        DBConnection icdb = DBConnection.connect(dbConfig.icdbSchema, dbConfig);
        final ExecuteQueryCommand executeQueryCommand = cmd.executeQueryCommand;

        StatisticsMetadata metadata = new StatisticsMetadata(
            dbConfig.codeGen.getAlgorithm(), dbConfig.granularity, dbConfig.icdbSchema,
            executeQueryCommand.fetch, executeQueryCommand.threads, executeQueryCommand.query
        );

        Statistics statistics = new Statistics(metadata, new File("./src/main/resources/statistics/data.csv"));
        RunStatistics run = new RunStatistics();
        statistics.addRun(run);

        executeQueryRun(
            executeQueryCommand.query, executeQueryCommand.fetch, executeQueryCommand.threads, dbConfig, run, true
        ,icdb);

        statistics.outputRuns();
    }


    /**
     * Executes a query for 5 runs
     */
    private static void executeQuerybenchmark(CommandLineArgs cmd, UserConfig dbConfig) {
        DBConnection icdb = DBConnection.connect(dbConfig.icdbSchema, dbConfig);
        final MultirunBenchmarkCommand executemultirunQueryCommand = cmd.multirunbenchmarkCommand;

        StatisticsMetadata metadata = new StatisticsMetadata(
                dbConfig.codeGen.getAlgorithm(), dbConfig.granularity, dbConfig.icdbSchema,
                executemultirunQueryCommand.fetch, executemultirunQueryCommand.threads, executemultirunQueryCommand.query
        );
        Statistics statistics = new Statistics(metadata, new File("./src/main/resources/statistics/data.csv"));


            for(int i=0; i<5; i++){
                RunStatistics run = new RunStatistics();
                run.setRun(i+1);
                statistics.addRun(run);



                if(executemultirunQueryCommand.insert){
                    //read the insert queries from the file and write to an datastructure to execute in a loop
                    //also track the conversio and execution time
                    StringBuilder builder = new StringBuilder();
                    try (Stream<String> stream = Files.lines(Paths.get(executemultirunQueryCommand.insertfile))) {
                        stream.forEach(line -> builder.append(line));
                        executeQueryRun(
                                builder.toString(), executemultirunQueryCommand.fetch, executemultirunQueryCommand.threads, dbConfig, run, true,icdb
                        );
                        icdb.getCreate().execute("Truncate table salaries;");

                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }else if(executemultirunQueryCommand.dbDelete){

                    //run delete query on the standard/original MySQL database
                    DBConnection DB = DBConnection.connect(dbConfig.schema, dbConfig);

                    Stopwatch queryExecutionTime = Stopwatch.createStarted();
                    DB.getCreate().execute(executemultirunQueryCommand.query);
                    run.setExecutionTime(queryExecutionTime.elapsed(ICDBTool.TIME_UNIT));
                    logger.debug("Total query execution time: {}", run.getExecutionTime());




                }
                else {

                    executeQueryRun(
                            executemultirunQueryCommand.query, executemultirunQueryCommand.fetch, executemultirunQueryCommand.threads, dbConfig, run, true, icdb
                    );
                }
            }


        statistics.outputRuns();
    }

    /**
     * Benchmarks insert, select, and delete queries from stdin
     * Note: VERY hacky (I was so frustrated I wanted it to work)
     */
    private static void benchmark(CommandLineArgs cmd, UserConfig dbConfig) {
        DBConnection icdb = DBConnection.connect(dbConfig.icdbSchema, dbConfig);
        final BenchmarkCommand benchmarkCommand = cmd.benchmarkCommand;
        final String dbSchema = benchmarkCommand.schemaName != null ? benchmarkCommand.schemaName : dbConfig.icdbSchema;

        final AlgorithmType algorithm = dbConfig.codeGen.getAlgorithm();
        final Granularity granularity = dbConfig.granularity;

        File[] insertFiles = new File(benchmarkCommand.insertPath).listFiles();
        File[] selectFiles = new File(benchmarkCommand.selectPath).listFiles();
        File[] deleteFiles = new File(benchmarkCommand.deletePath).listFiles();
        if (insertFiles == null || selectFiles == null || deleteFiles == null) {
            return;
        }

        Statistics insertStatistics = new Statistics(
            new StatisticsMetadata(
                    algorithm, granularity, dbSchema, benchmarkCommand.fetch, benchmarkCommand.threads, "insert"
            ),
            new File("./src/main/resources/statistics/" + algorithm + "-" + granularity + "-insert.csv")
        );
        Statistics selectStatistics = new Statistics(
                new StatisticsMetadata(
                        algorithm, granularity, dbSchema, benchmarkCommand.fetch, benchmarkCommand.threads, "select"
                ),
                new File("./src/main/resources/statistics/" + algorithm + "-" + granularity + "-select.csv")
        );
        Statistics deleteStatistics = new Statistics(
                new StatisticsMetadata(
                        algorithm, granularity, dbSchema, benchmarkCommand.fetch, benchmarkCommand.threads, "delete"
                ),
                new File("./src/main/resources/statistics/" + algorithm + "-" + granularity + "-delete.csv")
        );


        List<String> insertQueries = Arrays.stream(insertFiles)
            .sorted((f1, f2) -> f1.toString().compareTo(f2.toString()))
            .map(file -> {
                try { return FileUtils.readFileToString(file, Charsets.UTF_8); }
                catch (IOException e) { return null; }
            })
            .filter(s -> s != null)
            .collect(Collectors.toList());
        List<String> deleteQueries = Arrays.stream(deleteFiles)
            .sorted((f1, f2) -> f1.toString().compareTo(f2.toString()))
            .map(file -> {
                try { return FileUtils.readFileToString(file, Charsets.UTF_8); }
                catch (IOException e) { return null; }
            })
            .filter(s -> s != null)
            .collect(Collectors.toList());
        List<String> selectQueries = Arrays.stream(selectFiles)
                .sorted((f1, f2) -> f1.toString().compareTo(f2.toString()))
                .map(file -> {
                    try { return FileUtils.readFileToString(file, Charsets.UTF_8); }
                    catch (IOException e) { return null; }
                })
                .filter(s -> s != null)
                .collect(Collectors.toList());


        for (int i = 0; i < insertQueries.size(); i++) {
            final int numRuns = 5; // Number of the same run

            for (int j = 0; j < numRuns; j++) {
                RunStatistics insertRun = new RunStatistics();
                RunStatistics selectRun = new RunStatistics();
                RunStatistics deleteRun = new RunStatistics();
                insertRun.setRun(j+1);
                selectRun.setRun(j+1);
                deleteRun.setRun(j+1);

                // Insert values, then delete
                Stopwatch executionTime = Stopwatch.createStarted();

                if (benchmarkCommand.baseline) {
                    executeBaselineRun(insertQueries.get(i), dbConfig, insertRun);
                    executeBaselineRun(selectQueries.get(i), dbConfig, selectRun);
                    executeBaselineRun(deleteQueries.get(i), dbConfig, deleteRun);
                } else {
                    executeQueryRun(insertQueries.get(i), benchmarkCommand.fetch, benchmarkCommand.threads, dbConfig, insertRun, true,icdb);
                    executeQueryRun(selectQueries.get(i), benchmarkCommand.fetch, benchmarkCommand.threads, dbConfig, selectRun, true,icdb);
                    executeQueryRun(deleteQueries.get(i), benchmarkCommand.fetch, benchmarkCommand.threads, dbConfig, deleteRun, true,icdb);
                }

                logger.debug("Run time: {}", executionTime.elapsed(ICDBTool.TIME_UNIT));

                insertRun.setQueryFetchSize(deleteRun.getQueryFetchSize());

                deleteStatistics.addRun(deleteRun);
                selectStatistics.addRun(selectRun);
                insertStatistics.addRun(insertRun);
            }
        }

        deleteStatistics.outputRuns();
        selectStatistics.outputRuns();
        insertStatistics.outputRuns();
    }

    /**
     * Executes a query
     */
    private static void executeQueryRun(String query, DataSource.Fetch fetch, int threads, UserConfig dbConfig, RunStatistics run, boolean execute,DBConnection icdb) {

        ICDBQuery icdbQuery = dbConfig.granularity.getQuery(query, icdb, dbConfig.codeGen, run);

        logger.info("Original Query: {}", Format.limit(query));

        QueryVerifier verifier = dbConfig.granularity.getVerifier(icdb, dbConfig, threads, fetch, run);

        if (!icdbQuery.needsVerification()) {
            if (execute) {
                try {
                    verifier.execute(icdbQuery);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else if (verifier.verify(icdbQuery)) {
            logger.info("Query verified");
            if (execute) {
                try {
                    verifier.execute(icdbQuery);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            logger.info(Format.limit(icdbQuery.getVerifyQuery()));
            logger.info("Query failed to verify");
            logger.info("List of corrupted data items:");
            logger.info(verifier.getError());
        }
    }

    /**
     * Executes a query (baseline)
     */
    private static void executeBaselineRun(String query, UserConfig dbConfig, RunStatistics run) {
        DBConnection icdb = DBConnection.connect(dbConfig.icdbSchema, dbConfig);
        logger.info("Query: {}", Format.limit(query));

        Stopwatch executeTime = Stopwatch.createStarted();
        icdb.getCreate().execute(query);
        run.setExecutionTime(executeTime.elapsed(TIME_UNIT));


        logger.info("Execution time: {}", run.getExecutionTime());
    }

	static {
        System.setProperty("org.jooq.no-logo", "true");
	}

    public static void performTest()
            throws Exception
    {
        HashMap keymaps = new HashMap();


        BufferedReader br = null;

        try {


            String sCurrentLine;

            br = new BufferedReader(new FileReader("/Users/ujwal-mac/IdeaProjects/IntegrityCodedDatabase-ECelgamal/ICDB/src/main/resources/ecKeys"));

            while ((sCurrentLine = br.readLine()) != null) {
                String[] output = sCurrentLine.split("\\:");
                keymaps.put(output[0],output[1]);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (br != null)br.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

      BigInteger n = new BigInteger(keymaps.get("n").toString());
        System.out.print("the bit length of n is: "+n.bitLength());
        ECCurve.Fp curve = new ECCurve.Fp(
                new BigInteger(keymaps.get("q").toString()), // q
                new BigInteger(keymaps.get("a").toString(), 16), // a
                new BigInteger(keymaps.get("b").toString(), 16), // b
                n, ECConstants.ONE);
        ECDomainParameters params = new ECDomainParameters(
                curve,
                curve.decodePoint(Hex.decode(keymaps.get("G").toString())), // G
                n);
        ECPublicKeyParameters pubKey = new ECPublicKeyParameters(
                curve.decodePoint(Hex.decode(keymaps.get("Q").toString())), // Q
                params);
        ECPrivateKeyParameters priKey = new ECPrivateKeyParameters(
                new BigInteger(keymaps.get("d").toString()), // d
                params);
        ParametersWithRandom pRandom = new ParametersWithRandom(pubKey, new SecureRandom());
        BigInteger msg=new BigInteger("3433493988174819274891247819274819834853849238492849284290482094890890898748129489248912748219748921749128");
        BigInteger msg1=new BigInteger("4567493988174819274891247819274819834853849238492849284290482094890890898748129489248912748219748921749128");

        doTest(priKey,pubKey, pRandom, msg,msg1,curve);
        BigInteger rand = new BigInteger(pubKey.getParameters().getN().bitLength() - 1, new SecureRandom());
        System.out.print("the bit length of rand is: "+rand.bitLength());
        System.out.print("the bit length of msg is: "+msg.bitLength());
       // doTest(priKey, pRandom, rand,curve);
    }
    private static void doTest(ECPrivateKeyParameters priKey,ECPublicKeyParameters pubKey, ParametersWithRandom pRandom, BigInteger value,BigInteger value1,ECCurve.Fp curve)
    {
        ECPoint data = priKey.getParameters().getG().multiply(value);
        ECPoint data1 = priKey.getParameters().getG().multiply(value1);
        ECPoint muldata = data.add(data1);
        ECEncryptor encryptor = new ECElGamalEncryptor();
        encryptor.init(pRandom);

        ECPair pair = encryptor.encrypt(data);
        ECPair pair1 = encryptor.encrypt(data1);

        byte[] encodedx=pair.getX().getEncoded(true);
        BigInteger encx=new BigInteger(encodedx);
        byte[] encodedy=pair.getY().getEncoded(true);
        BigInteger ency=new BigInteger(encodedy);

        byte[] encodedx1=pair1.getX().getEncoded(true);
        BigInteger encx1=new BigInteger(encodedx1);
        byte[] encodedy1=pair1.getY().getEncoded(true);
        BigInteger ency1=new BigInteger(encodedy1);

       // encx=encx.multiply(encx).mod(pubKey.getParameters().getN());
       // ency=ency.multiply(ency).mod(pubKey.getParameters().getN());

       // byte[] multipliedx=encx.toByteArray();
       // byte[] multipliedy=ency.toByteArray();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream( );

        try {
            outputStream.write( encodedx );
            outputStream.write( encodedy );
        } catch (IOException e) {
            e.printStackTrace();
        }

        byte c[] = outputStream.toByteArray( );
        System.out.print("the bit length of cipher is: "+c.length*8);

        ECPoint decodedx = curve.decodePoint(Arrays.copyOfRange(c, 0, 25));
        ECPoint decodedy = curve.decodePoint(Arrays.copyOfRange(c, 25, 50));
        ECPair newpair= new ECPair(decodedx, decodedy);

        ECPoint decodedx1 = curve.decodePoint(encodedx1);
        ECPoint decodedy1 = curve.decodePoint(encodedy1);

        ECPoint muldecodedx = decodedx.add(decodedx1);
        ECPoint muldecodedy = decodedy.add(decodedy1);
        ECPair mulnewpair= new ECPair(muldecodedx, muldecodedy);


        ECDecryptor decryptor = new ECElGamalDecryptor();
        decryptor.init(priKey);
        ECPoint result = decryptor.decrypt(newpair);

        ECPoint mulresult = decryptor.decrypt(mulnewpair);

//        //test ECSigner class
        ECParams params=new ECParams();
        ECSigner ecsigner=new ECSigner(params);
        if(ecsigner.verify(ecsigner.computeECCode(value.toByteArray()),value.toByteArray())){
            System.out.println("ECSigner class verified");
        }


        if (!data.equals(result))
        {
            fail("point pair failed to decrypt back to original");
        }else{

            System.out.println("EC verified");
        }

        if (!muldata.equals(mulresult))
        {
            fail("point pair failed to decrypt back to original");
        }else{

            System.out.println("EC verified");
        }
    }




}
