/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.streams.client.example;

import org.apache.rocketmq.streams.client.StreamBuilder;
import org.apache.rocketmq.streams.client.transform.window.HoppingWindow;
import org.apache.rocketmq.streams.client.transform.window.Time;
import org.apache.rocketmq.streams.client.transform.window.TumblingWindow;
import org.junit.Test;

public class CountFromFileTest {

    /**
     * 窗口10分钟，触发前，每一分钟发送一次数据，过了触发时间，每5秒发一次数据
     */
    @Test
    public void testCountTumplFromFile() {
        StreamBuilder.dataStream("tmp", "tmp")
            .fromFile("window_msg_88121.txt", true)
            .window(TumblingWindow.of(Time.seconds(5)))
            .count("count_result")
            .toDataSteam()
            .toPrint()
            .start();
    }

    @Test
    public void testHopCountFromFile() {
        StreamBuilder.dataStream("tmp", "tmp")
            // filepath can be either a classpath file or an absolute path file address
            //filepath can set window_msg_10 or window_msg_100 window_msg_1000 or window_msg_10000
            .fromFile("window_msg_10000.txt", true)
            .window(HoppingWindow.of(Time.seconds(10), Time.seconds(5)))
            .count("count_result")
            .toDataSteam()
            .toPrint()
            .start();
    }

    //    @Test
    //    public void testSessionCountFromFile(){
    //        StreamBuilder.dataStream("tmp","tmp")
    //            // Filepath can be either a classpath file or an absolute path file address
    //            //filepath can set window_msg_10 or window_msg_100 window_msg_1000 or window_msg_10000
    //            .fromFile("window_msg_88121.txt",true)
    //            .window(SessionWindow.of(Time.seconds(5)))
    //            .count("count_result")
    //            .toDataSteam()
    //            .toPrint()
    //            .start();;
    //    }
}
