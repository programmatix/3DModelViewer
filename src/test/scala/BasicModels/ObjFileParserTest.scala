package BasicModels

import org.scalactic.TolerantNumerics
import org.scalatest.FunSuite

class ObjFileParserTest extends FunSuite {
  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)

  test("comment") {
    val out = ObjFileParser.parseLine("# this is a comment")
    assert (out.get(0).typ == ObjFileTokenType.Comment)
  }

  test("vertices") {
    val out = ObjFileParser.parseLine("v 0.123 0.234 0.345 1.0")
    assert (out.get(0).typ == ObjFileTokenType.VertexMarker)
    assert (out.get(1).asInstanceOf[ObjFileTokenFloat].typ === ObjFileTokenType.Vertex)
    assert (out.get(1).asInstanceOf[ObjFileTokenFloat].value === 0.123f)
  }

  test("faces complex") {
    val out = ObjFileParser.parseLine("f 1//1 2//1 3//1 4//1")
    assert (out.get(0).typ == ObjFileTokenType.FaceMarker)
    assert (out.get(1).asInstanceOf[ObjFileTokenFloat].typ === ObjFileTokenType.Vertex)
    assert (out.get(1).asInstanceOf[ObjFileTokenFloat].value === 0.123f)
  }

  test("real world") {
    val raw = """# Blender v2.79 (sub 0) OBJ File: 'cube.blend'
                |# www.blender.org
                |mtllib cube.mtl
                |o Cube
                |v 1.000000 -1.000000 -1.000000
                |v 1.000000 -1.000000 1.000000
                |v -1.000000 -1.000000 1.000000
                |v -1.000000 -1.000000 -1.000000
                |v 1.000000 1.000000 -0.999999
                |v 0.999999 1.000000 1.000001
                |v -1.000000 1.000000 1.000000
                |v -1.000000 1.000000 -1.000000
                |vn 0.0000 -1.0000 0.0000
                |vn 0.0000 1.0000 0.0000
                |vn 1.0000 0.0000 0.0000
                |vn -0.0000 -0.0000 1.0000
                |vn -1.0000 -0.0000 -0.0000
                |vn 0.0000 0.0000 -1.0000
                |usemtl Material
                |s off
                |f 1//1 2//1 3//1 4//1
                |f 5//2 8//2 7//2 6//2
                |f 1//3 5//3 6//3 2//3
                |f 2//4 6//4 7//4 3//4
                |f 3//5 7//5 8//5 4//5
                |f 5//6 1//6 4//6 8//6
                |""".stripMargin.split("\n")

    {
      val out = ObjFileParser.parseLine(raw(0))
      assert(out.get(0).typ == ObjFileTokenType.Comment)
    }

    {
      val out = ObjFileParser.parseLine(raw(2))
      assert(out.get(0).typ === ObjFileTokenType.MtlLibMarker)
      assert(out.get(1).typ === ObjFileTokenType.MtlLib)
      assert(out.get(1).asInstanceOf[ObjFileTokenString].value === "cube.mtl")
    }

    {
      val out = ObjFileParser.parseLine(raw(3))
      assert(out.get(0).typ === ObjFileTokenType.Unknown)
    }

    {
      val out = ObjFileParser.parseLine(raw(3))
      assert(out.get(0).typ === ObjFileTokenType.Unknown)
    }
  }
}