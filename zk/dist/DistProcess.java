/*
Copyright
All materials provided to the students as part of this course is the property of respective authors. Publishing them to third-party (including websites) is prohibited. Students may save it for their personal use, indefinitely, including personal cloud storage spaces. Further, no assessments published as part of this course may be shared with anyone else. Violators of this copyright infringement may face legal actions in addition to the University disciplinary proceedings.
©2022, Joseph D’Silva; ©2024, Bettina Kemme
*/
import java.io.*;

import java.util.*;

// To get the name of the host.
import java.net.*;

//To get the process id.
import java.lang.management.*;

import org.apache.zookeeper.*;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.KeeperException.*;
import org.apache.zookeeper.data.*;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.Watcher.Event.EventType;

import java.nio.charset.StandardCharsets;

// TODO
// Replace XX with your group number.
// You may have to add other interfaces such as for threading, etc., as needed.
// This class will contain the logic for both your manager process as well as the worker processes.
//  Make sure that the callbacks and watch do not conflict between your manager's logic and worker's logic.
//		This is important as both the manager and worker may need same kind of callbacks and could result
//			with the same callback functions.
//	For simplicity, so far all the code in a single class (including the callbacks).
//		You are free to break it apart into multiple classes, if that is your programming style or helps
//		you manage the code more modularly.
//	REMEMBER !! Managers and Workers are also clients of ZK and the ZK client library is single thread - Watches & CallBacks should not be used for time consuming tasks.
//		In particular, if the process is a worker, Watches & CallBacks should only be used to assign the "work" to a separate thread inside your program.


// Apache ZooKeeper documentation for easy access: https://zookeeper.apache.org/doc/r3.3.3/api/org/apache/zookeeper/ZooKeeper.html
public class DistProcess implements Watcher
{
	ZooKeeper zk;
	String zkServer, pinfo;
	boolean isManager=false;
	boolean isAssigned = false;


	DistProcess(String zkhost)
	{
		zkServer=zkhost;
		pinfo = ManagementFactory.getRuntimeMXBean().getName();
		System.out.println("DISTAPP : ZK Connection information : " + zkServer);
		System.out.println("DISTAPP : Process information : " + pinfo);
	}

	public void process(WatchedEvent e) {
	}

	// Try to become the manager.
	void runForManager() throws UnknownHostException, KeeperException, InterruptedException
	{
		//Try to create an ephemeral node to be the manager, put the hostname and pid of this process as the data.
		// This is an example of Synchronous API invocation as the function waits for the execution and no callback is involved..
		zk.create("/dist20/manager", pinfo.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
	}

	void newWorker() throws UnknownHostException, KeeperException, InterruptedException {
		// Worker node is ephemeral as it must be removed if the worker disconnects.
		zk.create("/dist20/workers/" + pinfo, "Idle".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
		// New worker starts off without any tasks assigned to it
		zk.create("/dist20/assigned/" + pinfo, "".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
	}

	void startProcess() throws IOException, UnknownHostException, KeeperException, InterruptedException
	{
		zk = new ZooKeeper(zkServer, 1000, this); //connect to ZK.
		try
		{
			runForManager();	// See if you can become the manager (i.e, no other manager exists)
			isManager=true;
			System.out.println("DISTAPP : Role : I will be functioning as manager");
			getTasks(); // Install monitoring on new tasks

		} catch(NodeExistsException nee)
		{
			isManager=false;

			System.out.println("Becoming manager failed: "+nee);
			System.out.println("DISTAPP : Role : I will be functioning as worker");

			newWorker();
			getWorkerTasks();
		}


	}



	// WATCHERS
	// Apache ZooKeeper documentation for reference
	// ================================================================================================================
	Watcher tasksChangeWatcher = new Watcher() {
		public void process(WatchedEvent e) {
			if(e.getType() == EventType.NodeChildrenChanged && e.getPath().equals("/dist20/tasks")) {getTasks();}

		}
	};

	Watcher idleWorkerWatcher = new Watcher() {
		public void process(WatchedEvent e) {
			if(e.getType() == EventType.NodeChildrenChanged && e.getPath().equals("/dist20/workers")) {
				getTasks();
			}
		}
	};

	Watcher workerAssignedWatcher = new Watcher() {
		public void process(WatchedEvent e) {
			if (e.getType() == EventType.NodeChildrenChanged && e.getPath().equals("/dist20/assigned/" + pinfo)) {

				// Get worker assigned tasks
				getWorkerTasks();
			}
		}
	};
	// ================================================================================================================


	// CALLBACKS
	// Apache Zookeeper documentation for reference
	// ================================================================================================================
	ChildrenCallback tasksGetChildrenCallback = new ChildrenCallback() {
		public void processResult(int rc, String path, Object ctx, List<String> children){
			for (String c : children) zk.getData(path + "/" + c, false, getDataCallback, c);

			// reset isAssigned
			isAssigned = false;
		}
	};

	ChildrenCallback workerProcessCallback = new ChildrenCallback() {
		public void processResult(int rc, String path, Object ctx, List<String> children) {
			for (String c : children) {
				zk.getData(path + "/" + c, null, ComputeTaskCallback, c);
			}
		}
	};

	DataCallback ComputeTaskCallback = new DataCallback() {
		public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat)  {
			ComputeData(data, (String) ctx, path);
		}
	};

	DataCallback getDataCallback = new DataCallback() {
		public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat)  {
			myTaskObj taskObj = new myTaskObj((String) ctx, data);
			zk.getChildren("/dist20/workers", idleWorkerWatcher, workersGetChildrenCallback, taskObj);
		}
	};

	ChildrenCallback workersGetChildrenCallback = new ChildrenCallback() {
		public void processResult(int rc, String path, Object ctx, List<String> children){
			for (String c : children) {
				String childPath = path + "/" + c;

				// Get worker's data in the child path
				if (!isAssigned) zk.getData(childPath, false, getWorkerDataCallback, (myTaskObj)ctx);
				else break;
			}
			
		}
	};

	DataCallback getWorkerDataCallback = new DataCallback() {
		public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
			try{
			String workerStatus = new String(data, StandardCharsets.UTF_8);
			myTaskObj taskObj = (myTaskObj) ctx;

			// Ensure that the worker is idle and task is not already assigned
			if (taskObj.workerPath == null && workerStatus.equals("Idle") && !isAssigned) {
				// Assign the task atomically
				taskObj.newWorkerAndPath(path);
				zk.create("/dist20/assigned/" + taskObj.workerName + "/" + taskObj.task, taskObj.data, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL, recreateTaskCallback, taskObj);

				// Set the worker's status to busy
				zk.setData("/dist20/workers/" + taskObj.workerName, taskObj.task.getBytes(), -1);

				// Set isAssigned to true
				isAssigned = true;

			}
		}catch (KeeperException ke){System.out.println("getWorkerDataCallback: " + ke);}
		catch (InterruptedException ie){System.out.println("getWorkerDataCallback: " + ie);}

		}
	};
	

	StringCallback recreateTaskCallback = new StringCallback() {
		public void processResult(int rc, String path, Object ctx, String name) {
			zk.setData(((myTaskObj) ctx).workerPath, "Working".getBytes(), -1, null, (myTaskObj) ctx);
		}
	};

	// ================================================================================================================

	void getTasks()
	{
		zk.getChildren("/dist20/tasks", tasksChangeWatcher, tasksGetChildrenCallback, null);
	}

	void getWorkerTasks()
	{
		zk.getChildren("/dist20/assigned/" + pinfo, workerAssignedWatcher, workerProcessCallback, null);
	}



	void ComputeData(byte[] taskSerial, String task, String path) {
		final byte[] finalTaskSerial = taskSerial;
		// Create a seperate thread to perform computation
		Thread thread = new Thread(
			() -> {
				// Re-construct our task object.
				try {
					ByteArrayInputStream bis = new ByteArrayInputStream(finalTaskSerial);
					ObjectInput in = new ObjectInputStream(bis);
					DistTask dt = (DistTask) in.readObject();

					// Execute the task.
					// TODO: Create a seperate thread that does the time consuming "work" and notify thread from here
					dt.compute();

					// Serialize our Task object back to a byte array!
					ByteArrayOutputStream bos = new ByteArrayOutputStream();
					ObjectOutputStream oos = new ObjectOutputStream(bos);
					oos.writeObject(dt); oos.flush();
					byte[] newTaskSerial = bos.toByteArray();
					// finalTaskSerial = bos.toByteArray();

					// Store it inside the result node.
					zk.delete(path, -1);
					zk.create("/dist20/tasks/"+task+"/result", newTaskSerial, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
					zk.setData("/dist20/workers/" + pinfo, "Idle".getBytes(), -1);
					//zk.create("/dist20/tasks/"+c+"/result", ("Hello from "+pinfo).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);


				}
				catch(NodeExistsException nee){System.out.println(nee);}
				catch(KeeperException ke){System.out.println("ComputeData(): " + ke);}
				catch(InterruptedException ie){System.out.println(ie);}
				catch(IOException io){System.out.println(io);}
				catch(ClassNotFoundException cne){System.out.println(cne);}
			}

		);

		thread.start();
		// Re-construct our task object.
		// try {
		// 	ByteArrayInputStream bis = new ByteArrayInputStream(taskSerial);
		// 	ObjectInput in = new ObjectInputStream(bis);
		// 	DistTask dt = (DistTask) in.readObject();

		// 	// Execute the task.
		// 	// TODO: Create a seperate thread that does the time consuming "work" and notify thread from here
		// 	dt.compute();

		// 	// Serialize our Task object back to a byte array!
		// 	ByteArrayOutputStream bos = new ByteArrayOutputStream();
		// 	ObjectOutputStream oos = new ObjectOutputStream(bos);
		// 	oos.writeObject(dt); oos.flush();
		// 	taskSerial = bos.toByteArray();

		// 	// Store it inside the result node.
		// 	zk.delete(path, -1);
		// 	zk.create("/dist20/tasks/"+task+"/result", taskSerial, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		// 	zk.setData("/dist20/workers/" + pinfo, "Idle".getBytes(), -1);
		// 	//zk.create("/dist20/tasks/"+c+"/result", ("Hello from "+pinfo).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		// }
		// catch(NodeExistsException nee){System.out.println(nee);}
		// catch(KeeperException ke){System.out.println(ke);}
		// catch(InterruptedException ie){System.out.println(ie);}
		// catch(IOException io){System.out.println(io);}
		// catch(ClassNotFoundException cne){System.out.println(cne);}
	}



		// Custom task object used to store task details. Useful when recreating a task is required
		class myTaskObj {
			byte[] data;
			String task;
			String workerName = null;
			String workerPath = null;
			myTaskObj(String task, byte[] data) {
				this.task = task;
				this.data = data;
			}
	
			void newWorkerAndPath(String workerPath) {
	
				// This function sets up the worker and its path
				this.workerPath = workerPath;
	
				// Worker corresponds to last part of the worker path
				String[] pathList = workerPath.split("/");
				int lastIndex = pathList.length - 1;
				this.workerName = pathList[lastIndex];
			}
		}

	public static void main(String args[]) throws Exception
	{
		//Create a new process
		//Read the ZooKeeper ensemble information from the environment variable.
		DistProcess dt = new DistProcess(System.getenv("ZKSERVER"));
		dt.startProcess();

		//Replace this with an approach that will make sure that the process is up and running forever.
		while(true);
	}
}
