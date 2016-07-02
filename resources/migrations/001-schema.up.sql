CREATE TABLE users (
       "email"      text,
       "name"       text,
       "password"   text,

       PRIMARY KEY (email)
       );

CREATE TABLE cars (
       "id"         serial,
       "user"       text references users(email),
       "brand"      text,
       "model"      text,
       "mileage"    integer,
       "year"       smallint,
       "photo"      text,

       PRIMARY KEY (id)
       );

CREATE TABLE repairs (
       "id"                  serial,
       "car"                 integer references cars(id),
       "date"                date,
       "price"               money,
       "service_description" text,

       PRIMARY KEY (id)
       );
