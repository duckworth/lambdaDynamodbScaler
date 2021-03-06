package be.quodlibet.lambdadynamodbscaler;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.GlobalSecondaryIndexDescription;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;

import java.io.IOException;
import java.util.Calendar;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * @author Dries Horions <dries@quodlibet.be>
 */
public class Scaler
{
    private static final String configBucketName = Optional
           .ofNullable(System.getenv("BUCKETNAME"))
           .orElse("default-bucket-name-replace");
    private static final String configKey = "scaler.properties";
    private static final Regions region = Regions.fromName(
           Optional
                  .ofNullable(System.getenv("AWS_DEFAULT_REGION"))
                  .orElse("us-east-1")
    );

    private Properties ScalingProperties;
    private AmazonS3 s3Client;
    private AmazonDynamoDBClient clnt;
    private DynamoDB dynamoDB;
    private LambdaLogger log;

    public Response scale(Object input, Context context)
    {
        if (context != null)
        {
            log = context.getLogger();
        }
        setup();
        //Get the hour that was started
        Calendar rightNow = Calendar.getInstance();
        String hour = String.format("%02d", rightNow.get(Calendar.HOUR_OF_DAY));
        String message = "Scaling for hour : " + hour + "\n";
        log(message);
        //Get the table names
        if (ScalingProperties.containsKey("tablenames"))
        {
            String value = (String) ScalingProperties.get("tablenames");
            String[] tableNames = value.split(",");
            for (String tableName : tableNames)
            {
                //Check if there is a change requested for this hour
                String readProp = hour + "." + tableName + ".read";
                String writeProp = hour + "." + tableName + ".write";
                if (ScalingProperties.containsKey(readProp)
                       && ScalingProperties.containsKey(writeProp))
                {
                    String readCapacity = ScalingProperties.getProperty(readProp);
                    String writeCapacity = ScalingProperties.getProperty(writeProp);
                    //Execute the scaling change
                    message += scaleTable(tableName, Long.parseLong(readCapacity), Long.parseLong(writeCapacity));
                }
                else
                {
                    log(tableName + "\n No values found for table.\n");
                    message += tableName + "\n No values found for table.\n";
                }
            }
        }
        else
        {
            log("tableNames parameter not found in properties file");
        }
        //Get the index names
        if (ScalingProperties.containsKey("indexnames"))
        {
            String value = (String) ScalingProperties.get("indexnames");
            String[] tableAndIndexNames = value.split(",");
            for (String tableAndIndexName : tableAndIndexNames)
            {
                String[] split = tableAndIndexName.split("%");
                if (split.length == 2)
                {
                    String tableName = split[0];
                    String indexName = split[1];
                    //Check if there is a change requested for this hour
                    String readProp = hour + "." + indexName + ".read";
                    String writeProp = hour + "." + indexName + ".write";
                    if (ScalingProperties.containsKey(readProp) && ScalingProperties.containsKey(writeProp))
                    {
                        String readCapacity = ScalingProperties.getProperty(readProp);
                        String writeCapacity = ScalingProperties.getProperty(writeProp);
                        //Execute the scaling change
                        message += scaleIndex(tableName, indexName, Long.parseLong(readCapacity), Long.parseLong(writeCapacity));
                    }
                    else
                    {
                        log("No values found for index " + tableAndIndexName);
                        message += "\nNo values found for index " + tableAndIndexName + "\n";
                    }
                }
                else
                {
                    message += tableAndIndexName + "\n Index name in wrong format (tableName:indexName).\n";
                }
            }
        }
        else
        {
            log("indexNames parameter not found in properties file");
        }
        log(message);
        Response response = new Response(true, message);
        return response;
    }

    /**
     * Ensure we can also test this locally without context
     *
     * @param message
     */
    private void log(String message)
    {
        if (log != null)
        {
            log.log(message);
        }
        else
        {
            System.out.println(message);
        }
    }

    private String scaleTable(String tableName, Long readCapacity, Long writeCapacity)
    {
        Table table = dynamoDB.getTable(tableName);
        ProvisionedThroughput tp = new ProvisionedThroughput();
        tp.setReadCapacityUnits(readCapacity);
        tp.setWriteCapacityUnits(writeCapacity);
        TableDescription d = table.describe();
        if (!Objects.equals(d.getProvisionedThroughput().getReadCapacityUnits(), readCapacity)
               || !Objects.equals(d.getProvisionedThroughput().getWriteCapacityUnits(), writeCapacity))
        {
            d = table.updateTable(tp);
            return tableName + "\nRequested read/write : " + readCapacity + "/" + writeCapacity
                   + "\nCurrent read/write :" + d.getProvisionedThroughput().getReadCapacityUnits() + "/"
                   + d.getProvisionedThroughput().getWriteCapacityUnits() + "\nStatus : " + d.getTableStatus() + "\n";
        }
        else
        {
            return tableName + "\n Requested throughput equals current throughput\n";
        }
    }

    private String scaleIndex(String tableName, String indexName, Long readCapacity, Long writeCapacity)
    {
        Table table = dynamoDB.getTable(tableName);
        ProvisionedThroughput tp = new ProvisionedThroughput();
        tp.setReadCapacityUnits(readCapacity);
        tp.setWriteCapacityUnits(writeCapacity);
        TableDescription d = table.describe();
        for (GlobalSecondaryIndexDescription indexDescription : d.getGlobalSecondaryIndexes())
        {
            if (Objects.equals(indexDescription.getIndexName(), indexName))
            {
                if (!Objects.equals(indexDescription.getProvisionedThroughput().getReadCapacityUnits(), readCapacity)
                       || !Objects.equals(indexDescription.getProvisionedThroughput().getWriteCapacityUnits(), writeCapacity))
                {
                    d = table.getIndex(indexName).updateGSI(tp);
                    return tableName + ":" + indexName + "\nRequested read/write : " + readCapacity + "/" + writeCapacity
                           + "\nCurrent read/write :" + d.getProvisionedThroughput().getReadCapacityUnits() + "/"
                           + d.getProvisionedThroughput().getWriteCapacityUnits() + "\nStatus : " + d.getTableStatus()
                           + "\n";
                }
                else
                {
                    return tableName + ":" + indexName + "\n Requested throughput equals current throughput.\n";
                }
            }
        }
        return tableName + ":" + indexName + "\n No index found.\n";
    }

    private void setup()
    {
        //Setup S3 client
        if (s3Client == null)
        {
            s3Client = new AmazonS3Client();
        }
        //Setup DynamoDB client
        if (clnt == null)
        {
            clnt = new AmazonDynamoDBClient(new DefaultAWSCredentialsProviderChain());
            dynamoDB = new DynamoDB(clnt);
            clnt.setRegion(Region.getRegion(region));
        }
        //Load properties from S3
        if (ScalingProperties == null)
        {
            try
            {
                ScalingProperties = new Properties();
                S3Object object = s3Client.getObject(new GetObjectRequest(configBucketName, configKey));
                S3ObjectInputStream stream = object.getObjectContent();
                ScalingProperties.load(stream);
            }
            catch (IOException ex)
            {
                log("Failed to read config file : " + configBucketName + "/" + configKey + "(" + ex.getMessage() + ")");
            }
        }
    }
}
