// Mongo.java

/**
 *      Copyright (C) 2008 10gen Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.mongodb;

import java.net.*;
import java.util.*;

/**
 * A database connection with internal pooling.
 * The following are equivalent, and all connect to the
 * local database running on the default port:
 *
 * <blockquote><pre>
 * Mongo mongo1 = new Mongo( "127.0.0.1" );
 * Mongo mongo2 = new Mongo( "127.0.0.1", 27017 );
 * Mongo mongo3 = new Mongo( new DBAddress( "127.0.0.1:27017", "test" ) )
 * </pre></blockquote>
 *
 * Mongo instances have connection pooling built in - see the requestStart
 * and requestDone methods for more information.
 * http://www.mongodb.org/display/DOCS/Java+Driver+Concurrency
 *
 * <h3>Connecting to a Replica Pair</h3>
 * <p>
 * You can connect to a 
 * <a href="http://www.mongodb.org/display/DOCS/Replica+Pairs">replica pair</a>
 * using the Java driver by passing two DBAddresses to the Mongo constructor.
 * For example:
 * </p>
 * <blockquote><pre>
 * DBAddress left = new DBAddress("localhost:27017/test");
 * DBAddress right = new DBAddress("localhost:27018/test");
 *
 * Mongo mongo = new Mongo(left, right);
 * </pre></blockquote>
 * 
 * <p>
 * If the master of a replica pair goes down, there will be a brief lag before
 * the slave becomes master.  Thus, your application should be prepared to catch
 * the exceptions that might be thrown in such a case: IllegalArgumentException,
 * MongoException, and MongoException.Network (depending on when the connection
 * drops).
 * </p>
 * <p>
 * Once the slave becomes master, the driver will begin using that connection
 * as the master connection and the exceptions will stop being thrown.
 * </p>
 */
public class Mongo {

    public static final int MAJOR_VERSION = 1;
    public static final int MINOR_VERSION = 4;

    public static DB connect( DBAddress addr ){
        return new Mongo( addr ).getDB( addr.getDBName() );
    }

    public Mongo()
        throws UnknownHostException , MongoException {
        this( new ServerAddress() );
    }

    /**
     *  Connects to the local mongo instance on default port.
     *
     *  @param host server to connect to
     *  @throws UnknownHostException if the database host cannot be resolved
     */
    public Mongo( String host )
        throws UnknownHostException , MongoException {
        this( new ServerAddress( host ) );
    }

    /**
     *  Connects to the local mongo instance on default port.
     *
     *  @param host server to connect to
     *  @param options options to use
     *  @throws UnknownHostException if the database host cannot be resolved
     */
    public Mongo( String host , MongoOptions options )
        throws UnknownHostException , MongoException {
        this( new ServerAddress( host ) , options );
    }

    /**
     * Connects to Mongo using a given host, port, and database.
     * @param host the database's host address
     * @param port the port on which the database is running
     * @throws UnknownHostException if the database host cannot be resolved
     */
    public Mongo( String host , int port )
        throws UnknownHostException , MongoException {
        this( new ServerAddress( host , port ) );
    }

    /**
     * Connects to Mongo using a given DBAddress
     * @see com.mongodb.DBAddress
     * @param addr the database address
     */
    public Mongo( ServerAddress addr )
        throws MongoException {
        this( addr , new MongoOptions() );
    }


    /**
     * Connects to Mongo using a given DBAddress
     * @see com.mongodb.DBAddress
     * @param addr the database address
     */
    public Mongo( ServerAddress addr , MongoOptions options )
        throws MongoException {
        _addr = addr;
        _addrs = null;
        _options = options;
        _connector = new DBTCPConnector( this , _addr );
        _connector._pickInitial();
    }

    /**
       creates a Mongo connection in paired mode
       * @param left left side of the pair
       * @param right right side of the pair
     */
    public Mongo( ServerAddress left , ServerAddress right )
        throws MongoException {
        this( left , right , new MongoOptions() );
    }

    /**
       creates a Mongo connection in paired mode
       * @param left left side of the pair
       * @param right right side of the pair
     */
    public Mongo( ServerAddress left , ServerAddress right , MongoOptions options )
        throws MongoException {
        _addr = null;
        _addrs = Arrays.asList( left , right );
        _options = options;
        _connector = new DBTCPConnector( this , _addrs );
        _connector._pickInitial();
    }
    
    public DB getDB( String dbname ){
        
        DB db = _dbs.get( dbname );
        if ( db != null )
            return db;
        
        synchronized ( _dbs ){
            db = _dbs.get( dbname );
            if ( db != null )
                return db;
            
            db = new DBApiLayer( dbname , _connector );

            _dbs.put( dbname , db );
            return db;
        }
    }
    
    public List<String> getDatabaseNames()
        throws MongoException {

        BasicDBObject cmd = new BasicDBObject();
        cmd.put("listDatabases", 1);
        

        BasicDBObject res = (BasicDBObject)getDB( "admin" ).command(cmd);

        if (res.getInt("ok" , 0 ) != 1 )
            throw new MongoException( "error listing databases : " + res );

        List l = (List)res.get("databases");

        List<String> list = new ArrayList<String>();

        for (Object o : l) {
            list.add(((BasicDBObject)o).getString("name"));
        }
        return list;
    }


    /**
     *  Drops the database if it exists.
     *
     * @param dbName name of database to drop
     */
    public void dropDatabase(String dbName)
        throws MongoException {
        
        getDB( dbName ).dropDatabase();
    }

    public String getVersion(){
        return MAJOR_VERSION + "." + MINOR_VERSION;
    }

    public String debugString(){
        return _connector.debugString();
    }

    public String getConnectPoint(){
        return _connector.getConnectPoint();
    }

    /** Gets the address of this database.
     * @return the address
     */
    public ServerAddress getAddress(){
        return _connector.getAddress();
    }

    public List<ServerAddress> getAllAddress() {
        List<ServerAddress> result = _connector.getAllAddress();
        if (result == null) {
            return Arrays.asList(getAddress());
        }
        return result;
    }

    /**
     * closes all open connections
     * this Mongo instance can be re-used however
     */
    public void close(){
        _connector.close();
    }
    
    final ServerAddress _addr;
    final List<ServerAddress> _addrs;
    final MongoOptions _options;
    final DBTCPConnector _connector;
    final Map<String,DB> _dbs = new HashMap<String,DB>();
}
