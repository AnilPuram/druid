/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.testing.utils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.sql.http.SqlQuery;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SqlQueryWithResults extends AbstractQueryWithResults<SqlQuery>
{

  @JsonCreator
  public SqlQueryWithResults(
      @JsonProperty("query") SqlQuery query,
      @JsonProperty("description") String description,
      @JsonProperty("expectedResults") List<Map<String, Object>> expectedResults
  )
  {
    super(query, description, expectedResults, Collections.emptyList());
  }

}
