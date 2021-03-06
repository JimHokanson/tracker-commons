package org.openworm.trackercommons

import fastparse.all._
import json._

object Parser {

  import Parts.{W, Digit => D}

  val Commons = "\"tracker-commons\"" ~ W(":") ~ "true"

  val Date = P(
    (D ~ D ~ D ~ D).! ~ "-" ~ (D ~ D).! ~ "-" ~ (D ~ D).! ~ "T" ~                   // Date
    (D ~ D).! ~         ":" ~ (D ~ D).! ~ ":" ~ (D ~ D ~ ("." ~ D.rep(1)).?).! ~    // Time
    ("Z" | (CharIn("+-") ~ D ~ D ~ ":" ~ D ~ D)).!.?                                // Locale
  ).map{ case (y,mo,d,h,mi,s,loc) => 
    val ss = s.toDouble
    val ssi = math.floor(ss).toInt
    val ssns = math.rint((ss - ssi)*1e9).toInt
    (java.time.LocalDateTime.of(y.toInt, mo.toInt, d.toInt, h.toInt, mi.toInt, ssi, ssns), loc.getOrElse(""))
  }

  val PositiveDouble = P(
    ( P("0." ~ "0".rep ~ CharIn("123456789") ~ D.rep) | 
      P(CharIn("123456789") ~ D.rep ~ ("." ~ D.rep(1)).?)
    ) ~ 
    (CharIn("eE") ~ CharIn("+-").? ~ D.rep(1)).?
  ).!.map(_.toDouble)

  val Units = P("\"units\"" ~ W(":") ~! Struct.Obj)

  val Meta = P("\"metadata\"" ~ W(":") ~! Struct.Obj)

  val Files = P("\"files\"" ~ W(":") ~! Struct.Obj)

  val Dat = P(
    "\"data\"" ~ W(":") ~! (
      Struct.Obj.map(x => Array(x)) |
      Struct.Arr.filter{ case ArrJ(xs) => xs.forall{ case ob: ObjJ => true; case _ => false } }.map{ case ArrJ(xs) => xs.collect{ case ob: ObjJ => ob } }
    )
  )

  val Single = {
    // Kludgy var-based method to parse something at most once
    var u: Option[Either[String, ObjJ]] = None
    var m: Option[Either[String, ObjJ]] = None
    var d: Option[Either[String, Array[ObjJ]]] = None
    var f: Option[Either[String, ObjJ]] = None
    var commons = false
    val custom = collection.mutable.AnyRefMap.empty[String, List[JSON]]
    (
      W("{") ~! Pass.map{_ => u = None; m = None; d = None; f = None; commons = false; custom.clear(); () } ~
      P(
        Commons.map{_ => commons = true; () } |
        Files.map{ x => f = if (f.isEmpty) Some(Right(x)) else Some(Left("Mulitple file specification blocks not supported")); () } |
        Units.map{ x => u = if (u.isEmpty) Some(Right(x)) else Some(Left("Multiple units blocks not supported")); () } |
        Meta.map{ x => m = if (m.isEmpty) Some(Right(x)) else Some(Left("Multiple metadata blocks not supported")); () } |
        Dat.map{ x => d = if (d.isEmpty) Some(Right(x)) else Some(Left("Multiple data blocks not supported (all should be in one array!)")); () } |
        Struct.KeyVal.map{ case (k,v) => if (k startsWith "@") { custom += (k, v :: custom.get(k).getOrElse(Nil)) }; () }
      ).rep(sep = W("," ~! Pass)) ~
      W("}")
    ).map(_ => (u, m, d, f, commons, custom.map{ case (k,vs) => k -> vs.reverse }))
  }

  def apply(s: String): Either[String, DataSet] = Single.parse(s) match {
    case Result.Success((ou, om, od, ofs, com, cust), _) => (ou, od) match {
      case (None, _) => Left("Not valid WCON--no units.")
      case (_, None) => Left("Not valid WCON--no data.")
      case (Some(Right(u)), Some(Right(d))) =>
        var someO, someC = false
        val uni: UnitMap = (UnitMap from u) match {
          case Left(l) => return Left(l)
          case Right(um) =>
            Seq("t", "x", "y").foreach{ e => if (um missing e) return Left("Units data does not contain entry for " + e) }
            um
        }
        val meta = om match {
          case None => Metadata.empty
          case Some(Left(l)) => return Left(l)
          case Some(Right(m)) => (Metadata from uni.fix(m)) match {
            case Left(l) => return Left(l)
            case Right(r) => r
          }
        }
        val datas = d.map{ di => (DataSet dataEntry uni.fix(di)) match {
          case Left(l) => return Left(l)
          case Right(d)=>
            // Make sure we know whether ox, oy, cx, cy were used
            if (!someO || !someC) d match {
              case Left(da) =>
                if (da.independentC) someC = true
                if (da.specifiedO) someO = true
              case Right(db) =>
                if (db.independentC) someC = true
                if (db.specifiedO) someO = true
            }
            d
        }}
        val fs = ofs match {
          case None => FileSet.empty
          case Some(Left(l)) => return Left(l)
          case Some(Right(f)) => (FileSet from uni.fix(f)) match {
            case Left(l) => return Left(l)
            case Right(r) => r
          }
        }
        if      (someO && !(uni.lookup.contains("ox") && uni.lookup.contains("oy"))) Left("Used ox/oy without units")
        else if (someC && !(uni.lookup.contains("cx") && uni.lookup.contains("cy"))) Left("Used cx/cy without units")
        else Right(DataSet(meta, uni, datas, fs, uni.fix(json.ObjJ(cust.toMap))))
      case (Some(Left(l)), _) => Left(l)
      case (_, Some(Left(l))) => Left(l)
    }
    case Result.Failure(t) => Left("Parsing failed--probably not a valid JSON object?\n"+t.toString)
  }
}
