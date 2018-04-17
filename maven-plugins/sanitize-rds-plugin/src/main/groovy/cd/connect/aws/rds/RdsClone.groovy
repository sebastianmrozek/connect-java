package cd.connect.aws.rds

import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.rds.AmazonRDS
import com.amazonaws.services.rds.AmazonRDSClientBuilder
import com.amazonaws.services.rds.model.*
import groovy.transform.CompileStatic

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Richard Vowles on 16/02/18.
 */
@CompileStatic
class RdsClone {
	AmazonRDS rdsClient;

	void initialize(String profile = null) {
		AWSCredentialsProviderChain chain = new DefaultAWSCredentialsProviderChain()

		if (profile != null) {
			chain = new AWSCredentialsProviderChain(Arrays.asList(new ProfileCredentialsProvider(profile)))
		}

		rdsClient = AmazonRDSClientBuilder.standard().withCredentials(chain).build()
	}

	String snapshotDatabase(String database, int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds, String snapshotOverride) {
		String snapshotName = snapshotOverride ?: database + "-" + System.currentTimeMillis()
		long start = System.currentTimeMillis()
		CreateDBSnapshotRequest snap = new CreateDBSnapshotRequest()
		  .withDBInstanceIdentifier(database)
			.withDBSnapshotIdentifier(snapshotName)

		rdsClient.createDBSnapshot(snap)

		long end = System.currentTimeMillis()

		println "snapshot ${snapshotName} created in ${(end-start)}ms (${snap}";

		boolean success = waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, { ->
			return "available".equals(snapshotStatus(snapshotName, database))
		});

		return success ? snapshotName : null;
	}

	String snapshotStatus(String snapshot, String database) {
		try {
			DescribeDBSnapshotsResult snapshots = rdsClient.describeDBSnapshots(new DescribeDBSnapshotsRequest()
				.withDBInstanceIdentifier(database)
				.withDBSnapshotIdentifier(snapshot))
			println "snapshots are ${snapshots} for ${snapshot} and db ${database}"
			if (snapshots && snapshots.getDBSnapshots() != null && snapshots.getDBSnapshots().size() > 0) {
				return snapshots.getDBSnapshots().first().getStatus()
			}

			return null
		} catch (Exception e) {
			return null
		}
	}

	List<String> discoverSnapshots(String database) {
		DescribeDBSnapshotsResult snapshots = rdsClient.describeDBSnapshots(new DescribeDBSnapshotsRequest()
			.withDBInstanceIdentifier(database)
		)

		return snapshots.DBSnapshots.collect({ it.getDBSnapshotIdentifier() })
	}

	void createDatabaseInstanceFromSnapshot(String database, String snapshot, String vpc,
	                                        List<VpcSecurityGroupMembership> snapshotVpcSecurityGroups,
	                                        List<DBSecurityGroupMembership> dbSecurityGroups,
	                                        int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds, CreateInstanceResult completed) {
		try {
			rdsClient.describeDBInstances(new DescribeDBInstancesRequest().withDBInstanceIdentifier(database))

			deleteDatabaseInstance(database, waitPeriodInMinutes, waitPeriodPollTimeInSeconds)
		} catch (DBInstanceNotFoundException dinfe) {}

		long end = System.currentTimeMillis()

		long start = System.currentTimeMillis()
		def restoreRequest = new RestoreDBInstanceFromDBSnapshotRequest()
			.withDBInstanceIdentifier(database)
			.withDBSnapshotIdentifier(snapshot)


		if (vpc) {
			restoreRequest.withDBSubnetGroupName(vpc)
		}

		DBInstance instance = rdsClient.restoreDBInstanceFromDBSnapshot(restoreRequest)

		println "created ${database} from instance ${snapshot} in ${end-start}ms"

		boolean success = waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, { ->
			return "available".equals(databaseStatus(database))
		})

		if (success) {
			if (snapshotVpcSecurityGroups || dbSecurityGroups) {
				def m = new ModifyDBInstanceRequest().withDBInstanceIdentifier(instance.getDBInstanceIdentifier())
				if (snapshotVpcSecurityGroups) {
					m.withVpcSecurityGroupIds(snapshotVpcSecurityGroups.collect({it.vpcSecurityGroupId}))
				}
				if (dbSecurityGroups) {
					m.withDBSecurityGroups(dbSecurityGroups.collect({it.getDBSecurityGroupName()}))
				}
				rdsClient.modifyDBInstance(m)
			}

			success = waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, { ->
				return "available".equals(databaseStatus(database))
			})
		}

		if (completed) {
			completed.result(success, getDatabaseInstance(database))
		}
	}

	// defaults to success = true if we aren't waiting for it
	protected boolean waitFor(int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds, Closure criteria) {
		boolean success = true;

		int waitPeriodInSeconds = waitPeriodInMinutes * 60

		while (waitPeriodPollTimeInSeconds > 0 && waitPeriodInSeconds > 0) {
			success = criteria()

			if (success) {
				break
			}

			Thread.sleep(waitPeriodPollTimeInSeconds * 1000)

			waitPeriodInSeconds -= waitPeriodPollTimeInSeconds

			println "${waitPeriodInSeconds} seconds left..."
		}

		return success
	}

	void deleteDatabaseInstance(String database, int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds) {
		rdsClient.deleteDBInstance(new DeleteDBInstanceRequest().withDBInstanceIdentifier(database).withSkipFinalSnapshot(true))

		if (waitPeriodInMinutes) {
			println "waiting for deletion"
			waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, {
				// when the database has gone away...
				try {
					rdsClient.describeDBInstances(new DescribeDBInstancesRequest().withDBInstanceIdentifier(database))
					
					return false
				} catch (DBInstanceNotFoundException nfe) {
					return true
				}
			})
		}
	}

	String databaseStatus(String database) {
		return getDatabaseInstance(database)?.DBInstanceStatus
	}
	

	DBInstance getDatabaseInstance(String database) {
		try {
			def instances = rdsClient.describeDBInstances(new DescribeDBInstancesRequest().withDBInstanceIdentifier(database))
			println "instances = ${instances}"
			return instances?.DBInstances?.first()
		} catch (Exception e) {
			return null
		}
	}

	void deleteDatabaseSnapshot(String snapshot) {
		rdsClient.deleteDBSnapshot(new DeleteDBSnapshotRequest().withDBSnapshotIdentifier(snapshot))
	}
}