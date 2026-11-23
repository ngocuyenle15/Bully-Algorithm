"""Sinh báo cáo đồ án dạng Word (.docx).

Cài:   py -m pip install -r docs/report/requirements.txt   (phiên bản đã pin)
Chạy:  py docs/report/build_report.py
Sau khi sinh: mở bằng Word, nhấn F9 (hoặc Update Field) để điền mục lục.
Kết quả: docs/BaoCao_DoAn_Bully.docx
"""
import os
from docx import Document
from docx.enum.section import WD_ORIENT
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt, Cm, RGBColor

from figures import make_all

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "BaoCao_DoAn_Bully.docx")
FIG = make_all(os.path.join(HERE, "build"))

doc = Document()

# ---------------------------------------------------------------- page & styles
for s in doc.sections:
    s.page_height, s.page_width = Cm(29.7), Cm(21.0)
    s.top_margin, s.bottom_margin = Cm(2.0), Cm(2.0)
    s.left_margin, s.right_margin = Cm(3.0), Cm(2.0)


def set_font(style, name="Times New Roman", size=13, bold=None, color=None):
    style.font.name = name
    style.font.size = Pt(size)
    rpr = style.element.get_or_add_rPr()
    fonts = rpr.find(qn("w:rFonts"))
    if fonts is None:
        fonts = OxmlElement("w:rFonts"); rpr.append(fonts)
    for attr in ("w:asciiTheme", "w:hAnsiTheme", "w:eastAsiaTheme", "w:cstheme"):
        fonts.attrib.pop(qn(attr), None)
    for attr in ("w:ascii", "w:hAnsi", "w:eastAsia", "w:cs"):
        fonts.set(qn(attr), name)
    if bold is not None:
        style.font.bold = bold
    if color is not None:
        style.font.color.rgb = color


normal = doc.styles["Normal"]
set_font(normal)
normal.paragraph_format.line_spacing = 1.3
normal.paragraph_format.space_after = Pt(6)

for level, size in ((1, 16), (2, 14), (3, 13)):
    st = doc.styles[f"Heading {level}"]
    set_font(st, size=size, bold=True, color=RGBColor(0, 0, 0))
    st.paragraph_format.space_before = Pt(18 if level == 1 else 12)
    st.paragraph_format.space_after = Pt(6)
    st.paragraph_format.keep_with_next = True

cap = doc.styles["Caption"]
set_font(cap, size=11, bold=False, color=RGBColor(0x33, 0x33, 0x33))
cap.font.italic = True

fig_no = [0]
tab_no = [0]


# ---------------------------------------------------------------- helpers
def p(text="", bold=False, italic=False, align=None, size=None, indent=True):
    para = doc.add_paragraph()
    if indent:
        para.paragraph_format.first_line_indent = Cm(1.0)
    para.alignment = align if align is not None else WD_ALIGN_PARAGRAPH.JUSTIFY
    _runs(para, text, bold, italic, size)
    return para


def _runs(para, text, bold=False, italic=False, size=None):
    """**đậm** và `code` đơn giản trong đoạn văn."""
    import re
    for part in re.split(r"(\*\*[^*]+\*\*|`[^`]+`)", text):
        if not part:
            continue
        if part.startswith("**"):
            r = para.add_run(part[2:-2]); r.bold = True
        elif part.startswith("`"):
            r = para.add_run(part[1:-1]); r.font.name = "Consolas"; r.font.size = Pt(11)
            r._element.rPr.rFonts.set(qn("w:hAnsi"), "Consolas")
        else:
            r = para.add_run(part); r.bold = bold
        r.italic = italic or None
        if size:
            r.font.size = Pt(size)


def bullets(items, style="List Bullet"):
    for it in items:
        para = doc.add_paragraph(style=style)
        para.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        para.paragraph_format.space_after = Pt(3)
        _runs(para, it)


def numbered(items):
    # Word's "List Number" style keeps counting across the whole document (a later list starts at 3, 4…),
    # so each list is numbered explicitly and restarts at 1.
    for i, it in enumerate(items, 1):
        para = doc.add_paragraph()
        para.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        para.paragraph_format.left_indent = Cm(1.0)
        para.paragraph_format.first_line_indent = Cm(-0.6)
        para.paragraph_format.space_after = Pt(3)
        _runs(para, f"{i}. {it}")


def h(text, level):
    return doc.add_heading(text, level=level)


def code(text):
    for line in text.strip("\n").split("\n"):
        para = doc.add_paragraph()
        para.paragraph_format.space_after = Pt(0)
        para.paragraph_format.line_spacing = 1.0
        para.paragraph_format.left_indent = Cm(0.5)
        r = para.add_run(line if line else " ")
        r.font.name = "Consolas"; r.font.size = Pt(10)
        r._element.rPr.rFonts.set(qn("w:hAnsi"), "Consolas")
        shd = OxmlElement("w:shd"); shd.set(qn("w:val"), "clear"); shd.set(qn("w:fill"), "F3F4F6")
        para._p.get_or_add_pPr().append(shd)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def figure(path, caption, width=15.5):
    doc.add_picture(path, width=Cm(width))
    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    fig_no[0] += 1
    c = doc.add_paragraph(f"Hình {fig_no[0]}. {caption}", style="Caption")
    c.alignment = WD_ALIGN_PARAGRAPH.CENTER


def table(header, rows, caption=None, widths=None):
    if caption:
        tab_no[0] += 1
        c = doc.add_paragraph(f"Bảng {tab_no[0]}. {caption}", style="Caption")
        c.alignment = WD_ALIGN_PARAGRAPH.CENTER
        c.paragraph_format.keep_with_next = True
    t = doc.add_table(rows=1, cols=len(header))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, text in enumerate(header):
        cell = t.rows[0].cells[i]
        cell.text = ""
        para = cell.paragraphs[0]; r = para.add_run(text); r.bold = True; r.font.size = Pt(11)
        shd = OxmlElement("w:shd"); shd.set(qn("w:val"), "clear"); shd.set(qn("w:fill"), "DCE6F2")
        cell._tc.get_or_add_tcPr().append(shd)
    for row in rows:
        cells = t.add_row().cells
        for i, text in enumerate(row):
            cells[i].text = ""
            para = cells[i].paragraphs[0]
            para.paragraph_format.first_line_indent = None
            _runs(para, str(text), size=11)
    if widths:
        # Word ignores per-cell widths while the table autofits; fix the layout and the grid columns too.
        t.autofit = False
        layout = OxmlElement("w:tblLayout"); layout.set(qn("w:type"), "fixed")
        t._tbl.tblPr.append(layout)
        for i, w in enumerate(widths):
            t.columns[i].width = Cm(w)
        for row in t.rows:
            for i, w in enumerate(widths):
                row.cells[i].width = Cm(w)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return t


def page_break():
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


def toc():
    para = doc.add_paragraph()
    run = para.add_run()
    for tag, text in (("begin", None), (None, 'TOC \\o "1-3" \\h \\z \\u'), ("separate", None), (None, None), ("end", None)):
        if tag:
            el = OxmlElement("w:fldChar"); el.set(qn("w:fldCharType"), tag); run._r.append(el)
        elif text:
            el = OxmlElement("w:instrText"); el.set(qn("xml:space"), "preserve"); el.text = text; run._r.append(el)
        else:
            t = OxmlElement("w:t"); t.text = "Nhấn chuột phải → Update Field (hoặc F9) để cập nhật mục lục."; run._r.append(t)


def page_numbers():
    footer = doc.sections[0].footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = footer.add_run()
    for tag, text in (("begin", None), (None, "PAGE"), ("end", None)):
        if tag:
            el = OxmlElement("w:fldChar"); el.set(qn("w:fldCharType"), tag); run._r.append(el)
        else:
            el = OxmlElement("w:instrText"); el.set(qn("xml:space"), "preserve"); el.text = text; run._r.append(el)


# =============================================================== BÌA
def cover():
    for text, size, bold in (
        ("BỘ GIÁO DỤC VÀ ĐÀO TẠO", 13, False),
        ("TRƯỜNG ĐẠI HỌC ……………………………", 13, True),
        ("KHOA CÔNG NGHỆ THÔNG TIN", 13, True),
    ):
        para = doc.add_paragraph(); para.alignment = WD_ALIGN_PARAGRAPH.CENTER
        r = para.add_run(text); r.font.size = Pt(size); r.bold = bold
    for _ in range(3):
        doc.add_paragraph()
    para = doc.add_paragraph(); para.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = para.add_run("BÁO CÁO ĐỒ ÁN MÔN HỌC\nHỆ THỐNG PHÂN TÁN"); r.font.size = Pt(16); r.bold = True
    doc.add_paragraph()
    para = doc.add_paragraph(); para.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = para.add_run("ĐỀ TÀI:\nLOẠI TRỪ TƯƠNG HỖ TRONG HỆ PHÂN TÁN\nSỬ DỤNG GIẢI THUẬT BẦU CHỌN BULLY")
    r.font.size = Pt(20); r.bold = True
    for _ in range(3):
        doc.add_paragraph()
    for label in ("Giảng viên hướng dẫn:  ………………………………",
                  "Sinh viên thực hiện:    ……………………  MSSV: …………",
                  "                                  ……………………  MSSV: …………",
                  "Lớp:                           ………………………"):
        para = doc.add_paragraph(); para.paragraph_format.left_indent = Cm(3)
        para.add_run(label).font.size = Pt(13)
    for _ in range(3):
        doc.add_paragraph()
    para = doc.add_paragraph(); para.alignment = WD_ALIGN_PARAGRAPH.CENTER
    para.add_run("Tháng 11 năm 2026").italic = True
    page_break()


cover()
para = doc.add_paragraph(); para.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = para.add_run("MỤC LỤC"); r.bold = True; r.font.size = Pt(16)
toc()
page_break()

# =============================================================== MỞ ĐẦU
h("MỞ ĐẦU", 1)
p("Trong các hệ thống tập trung, việc bảo đảm tại một thời điểm chỉ có một tiến trình được truy cập "
  "tài nguyên dùng chung (critical section – đoạn găng) được giải quyết bằng các cơ chế của hệ điều hành "
  "như semaphore, mutex hay monitor, vốn dựa trên bộ nhớ dùng chung và một đồng hồ chung. Khi các tiến "
  "trình nằm trên nhiều máy tính khác nhau và chỉ trao đổi bằng thông điệp qua mạng, những giả thiết đó "
  "không còn đúng. Bài toán **loại trừ tương hỗ phân tán** vì thế trở thành một trong những bài toán "
  "kinh điển của môn Hệ thống phân tán.")
p("Giải pháp đơn giản và hiệu quả nhất về số thông điệp là dùng một tiến trình điều phối (Coordinator) "
  "trung tâm cấp quyền. Điểm yếu chí mạng của nó là điểm lỗi đơn (single point of failure): khi "
  "Coordinator hỏng, toàn hệ thống dừng. Do đó giải thuật loại trừ tương hỗ tập trung luôn phải đi kèm "
  "một **giải thuật bầu chọn** (election algorithm) để các tiến trình còn sống tự chọn ra Coordinator mới. "
  "Đồ án này hiện thực tổ hợp đó: một cụm 5 server-node chạy **giải thuật Bully** để bầu Coordinator; "
  "Coordinator cấp quyền truy cập một tài nguyên găng (dịch vụ xử lý PDF `document-worker`) cho các "
  "client-node theo thứ tự FIFO.")
p("Mục tiêu của đồ án:")
bullets([
    "Trình bày cơ sở lý thuyết về hệ thống phân tán, các giải thuật loại trừ tương hỗ và các giải thuật bầu chọn.",
    "Thiết kế và cài đặt hệ thống gồm server-node (Bully), client-node và document-worker, chạy thật trên mạng LAN `192.168.0.0/24`.",
    "Bảo đảm hai tính chất: (1) **an toàn** – tại mọi thời điểm có tối đa một client giữ quyền; (2) **sống** – khi Coordinator hỏng, cụm tự bầu Coordinator mới là node có ID lớn nhất còn hoạt động và client tiếp tục được phục vụ.",
    "Kiểm thử bằng các kịch bản lỗi thực tế: Coordinator chết, node ID cao khởi động lại, client chết khi đang chờ hoặc đang giữ quyền.",
])
p("Báo cáo gồm 6 chương: Chương 1 tổng quan hệ thống phân tán; Chương 2 loại trừ tương hỗ; Chương 3 giải "
  "thuật bầu chọn; Chương 4 phân tích – thiết kế hệ thống; Chương 5 cài đặt và triển khai trên LAN; "
  "Chương 6 kiểm thử, đánh giá; cuối cùng là kết luận và hướng phát triển.")
page_break()

# =============================================================== CHƯƠNG 1
h("CHƯƠNG 1. TỔNG QUAN VỀ HỆ THỐNG PHÂN TÁN", 1)
h("1.1. Khái niệm", 2)
p("Theo Tanenbaum và van Steen, **hệ thống phân tán** là “một tập hợp các phần tử tính toán độc lập, "
  "xuất hiện với người dùng như một hệ thống nhất quán duy nhất”. Hai ý quan trọng của định nghĩa là: "
  "(1) các phần tử (node) là độc lập – mỗi node có CPU, bộ nhớ, đồng hồ riêng và có thể hỏng độc lập; "
  "(2) các node phải phối hợp với nhau – và cách duy nhất để phối hợp là **truyền thông điệp** qua mạng.")
p("Coulouris định nghĩa tương tự: hệ phân tán là hệ thống mà các thành phần đặt trên các máy tính nối mạng, "
  "liên lạc và phối hợp hành động chỉ bằng cách truyền thông điệp. Từ đó suy ra ba hệ quả đặc trưng:")
bullets([
    "**Tính đồng thời (concurrency):** nhiều tiến trình chạy song song và tranh chấp tài nguyên dùng chung.",
    "**Không có đồng hồ toàn cục (no global clock):** các đồng hồ vật lý bị trôi; không thể dựa vào thời gian tuyệt đối để sắp thứ tự sự kiện giữa các máy.",
    "**Lỗi độc lập (independent failures):** một máy hoặc một đường mạng có thể hỏng trong khi phần còn lại vẫn chạy; các node khác thường không phân biệt được “node chậm” với “node chết”.",
])

h("1.2. Mục tiêu thiết kế", 2)
table(["Mục tiêu", "Ý nghĩa", "Liên hệ với đồ án"], [
    ["Chia sẻ tài nguyên", "Nhiều người dùng truy cập chung một tài nguyên", "Nhiều client dùng chung document-worker"],
    ["Trong suốt (transparency)", "Che giấu vị trí, sự di trú, lỗi, sự nhân bản…", "Client không cần biết server nào là Coordinator; failover tự động"],
    ["Tính mở (openness)", "Giao diện chuẩn, dễ mở rộng", "Giao thức JSON/TCP và gRPC/Protocol Buffers"],
    ["Khả năng mở rộng (scalability)", "Tăng số node/người dùng mà hiệu năng không suy giảm mạnh", "Discovery động qua UDP multicast"],
    ["Chịu lỗi (fault tolerance)", "Hệ thống vẫn phục vụ khi một phần hỏng", "Bầu lại Coordinator bằng Bully, lease/fencing"],
], "Các mục tiêu thiết kế của hệ phân tán", widths=[3.5, 6, 6.5])

h("1.3. Mô hình hệ thống", 2)
h("1.3.1. Mô hình tương tác: đồng bộ và bất đồng bộ", 3)
p("Trong **hệ đồng bộ** (synchronous), thời gian thực thi mỗi bước, độ trễ truyền thông điệp và độ trôi đồng hồ "
  "đều có cận trên đã biết. Trong **hệ bất đồng bộ** (asynchronous), không có cận nào như vậy. Kết quả "
  "nổi tiếng FLP (Fischer, Lynch, Paterson, 1985) chứng minh rằng trong hệ bất đồng bộ, chỉ cần một tiến trình "
  "có thể bị crash thì không tồn tại giải thuật đồng thuận tất định luôn kết thúc. Vì bầu chọn Leader tương "
  "đương với một dạng đồng thuận, mọi giải thuật bầu chọn thực tế (kể cả Bully) đều phải **giả thiết hệ gần "
  "đồng bộ**: dùng timeout để phát hiện lỗi.")
p("Mạng LAN của đồ án (switch Ethernet, cùng một broadcast domain, RTT dưới 1 ms) thỏa mãn khá tốt giả thiết "
  "này: timeout 1 giây lớn hơn RTT hàng nghìn lần nên xác suất nghi ngờ nhầm là thấp.")
h("1.3.2. Mô hình lỗi", 3)
table(["Loại lỗi", "Mô tả", "Xử lý trong đồ án"], [
    ["Crash (fail-stop)", "Tiến trình dừng hẳn, không gửi gì nữa", "Heartbeat timeout → bầu lại; lease hết hạn"],
    ["Omission", "Mất thông điệp gửi/nhận", "TCP tin cậy; client gửi lại với cùng requestId (idempotent)"],
    ["Timing", "Phản hồi quá chậm so với cận", "Coi như lỗi sau N lần timeout liên tiếp"],
    ["Byzantine", "Hành vi tùy ý, kể cả gian lận", "Ngoài phạm vi (giả định LAN tin cậy)"],
], "Mô hình lỗi", widths=[3, 6, 7])

h("1.4. Thời gian và trạng thái toàn cục", 2)
p("Do không có đồng hồ toàn cục, Lamport (1978) đưa ra quan hệ **xảy ra trước** (happened-before, ký hiệu →) "
  "và **đồng hồ logic**: mỗi tiến trình giữ bộ đếm C; tăng C trước mỗi sự kiện; gắn C vào thông điệp gửi đi; "
  "khi nhận thông điệp mang dấu t thì đặt C = max(C, t) + 1. Nếu a → b thì C(a) < C(b). Đồng hồ vector "
  "(Fidge, Mattern) mở rộng để phát hiện cả quan hệ song song. Các giải thuật loại trừ tương hỗ phân tán "
  "thuần túy như Lamport hay Ricart–Agrawala dựa trực tiếp vào đồng hồ logic để sắp thứ tự yêu cầu.")
p("Trong đồ án, vai trò “sắp thứ tự toàn cục” được giao cho một thực thể duy nhất: Coordinator sắp yêu cầu "
  "theo FIFO; Worker cấp **generation** tăng đơn điệu cho mỗi nhiệm kỳ Coordinator. Generation chính là một "
  "dạng đồng hồ logic cho các nhiệm kỳ (tương tự *term* trong Raft hay *epoch* trong ZooKeeper).")

h("1.5. Truyền thông trong hệ phân tán", 2)
bullets([
    "**TCP:** kênh tin cậy, có thứ tự, hướng kết nối. Đồ án dùng TCP gửi thông điệp JSON (mỗi dòng một thông điệp) cho Bully, heartbeat và giao thức client–server.",
    "**UDP multicast:** một gói gửi tới nhóm địa chỉ (239.255.42.99) được mọi thành viên nhận. Đồ án dùng để các server tự khám phá nhau (membership).",
    "**RPC / gRPC:** gọi thủ tục từ xa, che giấu việc truyền thông điệp. gRPC dùng HTTP/2 và Protocol Buffers; đồ án dùng cho giao tiếp với document-worker.",
])
page_break()

# =============================================================== CHƯƠNG 2
h("CHƯƠNG 2. GIẢI THUẬT LOẠI TRỪ TƯƠNG HỖ PHÂN TÁN", 1)
h("2.1. Phát biểu bài toán", 2)
p("Cho N tiến trình P1…PN chỉ liên lạc bằng thông điệp, cùng chia sẻ một tài nguyên R. Đoạn mã truy cập R "
  "gọi là **đoạn găng** (critical section – CS). Mỗi tiến trình lặp: `enter()` → truy cập R → `exit()`. "
  "Giải thuật phải thỏa mãn:")
bullets([
    "**ME1 – An toàn (safety):** tại mọi thời điểm có tối đa một tiến trình ở trong CS.",
    "**ME2 – Sống (liveness):** mọi yêu cầu vào CS cuối cùng đều được đáp ứng; không deadlock, không starvation.",
    "**ME3 – Thứ tự (fairness):** nếu yêu cầu A xảy ra trước yêu cầu B (theo quan hệ →) thì A được vào CS trước B.",
])
p("Các tiêu chí đánh giá: (1) số thông điệp cho mỗi lần vào/ra CS; (2) độ trễ đồng bộ (synchronization delay) – "
  "thời gian từ khi một tiến trình rời CS đến khi tiến trình kế tiếp vào được; (3) thông lượng; (4) khả năng chịu lỗi.")

h("2.2. Giải thuật tập trung (Centralized)", 2)
p("Một tiến trình được chọn làm **Coordinator**. Muốn vào CS, tiến trình gửi REQUEST tới Coordinator. Nếu CS "
  "rảnh, Coordinator trả GRANT; nếu không, đưa yêu cầu vào hàng đợi (không trả lời hoặc trả QUEUED). Khi ra "
  "khỏi CS, tiến trình gửi RELEASE; Coordinator lấy yêu cầu đầu hàng đợi và gửi GRANT.")
bullets([
    "Mỗi lần vào/ra CS chỉ cần **3 thông điệp** (REQUEST, GRANT, RELEASE); độ trễ đồng bộ = 2 thông điệp.",
    "Thỏa ME1, ME2 và thứ tự FIFO theo thời điểm Coordinator nhận yêu cầu.",
    "Nhược điểm: Coordinator là **điểm lỗi đơn** và có thể là nút cổ chai; client không phân biệt được “đang chờ” và “Coordinator đã chết” nếu Coordinator không phản hồi.",
])

h("2.3. Giải thuật Lamport (1978)", 2)
p("Mỗi tiến trình giữ hàng đợi yêu cầu sắp theo dấu thời gian Lamport (timestamp, id). Muốn vào CS, Pi gửi "
  "REQUEST(Ti, i) tới mọi tiến trình khác và đưa vào hàng đợi của mình. Pj nhận REQUEST thì đưa vào hàng đợi "
  "và trả REPLY. Pi vào CS khi yêu cầu của nó đứng đầu hàng đợi và đã nhận được thông điệp có timestamp lớn "
  "hơn từ mọi tiến trình khác. Khi ra, Pi gửi RELEASE tới tất cả. Chi phí **3(N−1)** thông điệp; đòi hỏi kênh FIFO.")

h("2.4. Giải thuật Ricart–Agrawala (1981)", 2)
p("Cải tiến Lamport bằng cách gộp RELEASE vào REPLY. Pi gửi REQUEST(Ti, i) tới N−1 tiến trình. Pj trả REPLY "
  "ngay nếu Pj không muốn vào CS, hoặc đang chờ nhưng yêu cầu của Pj có (timestamp, id) lớn hơn; ngược lại Pj "
  "trì hoãn REPLY tới khi ra khỏi CS. Pi vào CS khi nhận đủ N−1 REPLY. Chi phí **2(N−1)** thông điệp. "
  "Nhược điểm: có N điểm lỗi thay vì 1 – chỉ cần một tiến trình chết là mọi tiến trình khác chờ vô hạn.")

h("2.5. Giải thuật Maekawa (1985)", 2)
p("Mỗi tiến trình Pi có một **tập bầu chọn** (quorum) Vi kích thước ≈ √N sao cho hai tập bất kỳ giao nhau. "
  "Pi chỉ cần xin phép các thành viên Vi; mỗi thành viên chỉ “bỏ phiếu” cho một yêu cầu tại một thời điểm. "
  "Chi phí **3√N** thông điệp, nhưng giải thuật gốc có thể deadlock và cần thêm thông điệp INQUIRE/RELINQUISH.")

h("2.6. Giải thuật dựa trên thẻ bài (Token-based)", 2)
p("**Token Ring:** các tiến trình xếp thành vòng logic, một token lưu chuyển; chỉ tiến trình giữ token được vào "
  "CS. Đơn giản, công bằng nhưng tốn băng thông khi không ai cần CS và khó xử lý mất token. "
  "**Suzuki–Kasami (1985):** tiến trình cần CS broadcast REQUEST kèm số thứ tự; token mang hàng đợi và mảng "
  "số thứ tự đã phục vụ; chi phí N thông điệp hoặc 0 nếu đã giữ token.")

h("2.7. So sánh", 2)
table(["Giải thuật", "Thông điệp / lần vào CS", "Độ trễ đồng bộ", "Điểm lỗi", "Ghi chú"], [
    ["Tập trung", "3", "2", "Coordinator (1)", "Cần bầu chọn để thay Coordinator"],
    ["Lamport", "3(N−1)", "1", "N", "Cần kênh FIFO"],
    ["Ricart–Agrawala", "2(N−1)", "1", "N", "Không cần RELEASE riêng"],
    ["Maekawa", "≈3√N", "2", "≈√N", "Có thể deadlock"],
    ["Token Ring", "1 → ∞", "0 → N−1", "Mất token, node hỏng", "Thứ tự theo vòng"],
    ["Suzuki–Kasami", "0 hoặc N", "0 hoặc 1", "Mất token", "Dựa trên broadcast"],
], "So sánh các giải thuật loại trừ tương hỗ", widths=[3, 3, 2.3, 3, 4.7])

h("2.8. Lựa chọn của đồ án", 2)
p("Đồ án chọn **giải thuật tập trung** vì: chi phí thông điệp thấp nhất (3), dễ bảo đảm FIFO, dễ hiện thực và "
  "dễ quan sát khi bảo vệ. Điểm yếu điểm lỗi đơn được khắc phục bằng hai cơ chế:")
numbered([
    "**Bầu chọn Bully** giữa các server-node để luôn có một Coordinator là node ID lớn nhất còn sống.",
    "**Tách “quyền điều phối” khỏi “trạng thái tài nguyên”**: session của client nằm ở document-worker, không nằm ở Coordinator. Nhờ đó khi Coordinator chết, client đang giữ quyền vẫn giữ được session; Coordinator mới chỉ cần nhận lại yêu cầu của các client đang chờ.",
])
p("Để ngăn hai Coordinator cùng cấp quyền (split-brain) trong lúc chuyển giao, Worker cấp **lease có thời hạn** "
  "kèm **generation** (fencing token). Mọi thao tác RESERVE/RELEASE phải mang đúng generation hiện hành.")
page_break()

# =============================================================== CHƯƠNG 3
h("CHƯƠNG 3. GIẢI THUẬT BẦU CHỌN", 1)
h("3.1. Bài toán bầu chọn", 2)
p("Nhiều giải thuật phân tán cần một tiến trình đóng vai trò đặc biệt (coordinator, leader, sequencer). "
  "Bài toán bầu chọn yêu cầu: khi khởi động hoặc khi coordinator hỏng, các tiến trình còn sống phải thống nhất "
  "chọn ra đúng một coordinator. Hai tính chất:")
bullets([
    "**E1 – An toàn:** mọi tiến trình tham gia đều chọn cùng một coordinator, và đó là tiến trình còn sống có định danh lớn nhất (hoặc chưa chọn ai).",
    "**E2 – Sống:** mọi tiến trình còn sống cuối cùng đều chọn được coordinator (hoặc bị crash).",
])
p("Giả thiết chung: mỗi tiến trình có **ID duy nhất, cố định, có thứ tự toàn phần**; mỗi tiến trình biết ID "
  "(và địa chỉ) của các tiến trình khác; kênh truyền tin cậy; lỗi là crash.")

h("3.2. Giải thuật Bully (Garcia-Molina, 1982)", 2)
p("Bully giả thiết **hệ đồng bộ**: có cận trên T cho độ trễ thông điệp và thời gian xử lý, vì thế có thể "
  "dùng timeout để phát hiện tiến trình chết. Tên gọi “bully” (kẻ bắt nạt) xuất phát từ việc tiến trình có ID "
  "lớn nhất luôn “giành” quyền coordinator. Giải thuật dùng ba loại thông điệp:")
table(["Thông điệp", "Ý nghĩa", "Tên trong mã nguồn"], [
    ["ELECTION", "Thông báo bắt đầu bầu chọn, gửi tới các tiến trình có ID lớn hơn", "`ELECTION`"],
    ["ANSWER (OK)", "Phản hồi: “tôi còn sống và sẽ tiếp quản”", "`ELECTION_OK`"],
    ["COORDINATOR", "Công bố coordinator mới tới mọi tiến trình", "`COORDINATOR`"],
], "Ba thông điệp của Bully", widths=[3, 8.5, 4.5])
p("Khi tiến trình Pi phát hiện coordinator không phản hồi (hoặc khi vừa khởi động):")
code("""
Bully(Pi):
  gửi ELECTION tới mọi Pj có j > i
  nếu không nhận được ANSWER nào trong thời gian T:
      Pi là coordinator; gửi COORDINATOR(i) tới mọi Pj có j < i
  ngược lại (đã nhận ANSWER):
      chờ COORDINATOR trong thời gian T'
      nếu hết T' mà không có COORDINATOR: chạy lại Bully(Pi)

Khi Pj nhận ELECTION từ Pi (i < j):
  trả ANSWER cho Pi
  nếu Pj chưa đang bầu chọn: chạy Bully(Pj)

Khi Pj nhận COORDINATOR(k):
  ghi nhận coordinator = k
""")
p("**Tiến trình phục hồi:** khi một tiến trình có ID lớn hơn coordinator hiện tại khởi động lại, nó chạy Bully "
  "và – vì không ai lớn hơn trả ANSWER – nó công bố chính mình là coordinator, “bắt nạt” coordinator hiện tại.")
h("3.2.1. Ví dụ", 3)
p("Cụm 5 tiến trình, coordinator là P5. P5 crash. P1 phát hiện trước: gửi ELECTION tới P2, P3, P4. Cả ba trả "
  "ANSWER và tự chạy Bully. P2 gửi tới P3, P4; P3 gửi tới P4; P4 gửi tới P5 nhưng không có phản hồi. Hết "
  "timeout, P4 tự xưng coordinator và gửi COORDINATOR(4) tới P1, P2, P3 (Hình 2, Chương 4).")
h("3.2.2. Độ phức tạp", 3)
bullets([
    "**Tốt nhất:** tiến trình có ID lớn thứ hai phát hiện lỗi → gửi N−2 thông điệp COORDINATOR (cộng ELECTION tới node đã chết): **O(N)**.",
    "**Xấu nhất:** tiến trình có ID nhỏ nhất phát hiện lỗi → mọi tiến trình đều khởi động bầu chọn: (N−1) + (N−2) + … + 1 ELECTION và bấy nhiêu ANSWER → **O(N²)** thông điệp.",
    "Thời gian: tối đa khoảng một timeout T cho pha ELECTION cộng T' chờ COORDINATOR, nếu các ELECTION được gửi song song.",
])
h("3.2.3. Ưu, nhược điểm", 3)
bullets([
    "Ưu: đơn giản, hội tụ nhanh, kết quả tất định (luôn là ID lớn nhất còn sống).",
    "Nhược: số thông điệp O(N²) trong trường hợp xấu; phụ thuộc giả thiết đồng bộ – nếu timeout quá nhỏ, node chậm bị coi là chết và có thể xuất hiện **hai coordinator** (split-brain); node ID lớn liên tục khởi động lại sẽ gây bầu chọn liên tục.",
])

h("3.3. Giải thuật vòng (Ring – Chang & Roberts 1979, LeLann 1977)", 2)
p("Các tiến trình xếp thành vòng logic, mỗi tiến trình biết tiến trình kế tiếp. Tiến trình phát hiện lỗi gửi "
  "ELECTION chứa danh sách [ID của mình] tới tiến trình kế tiếp (bỏ qua tiến trình chết). Mỗi tiến trình nhận "
  "thêm ID của mình vào danh sách rồi chuyển tiếp. Khi thông điệp quay về người khởi xướng, ID lớn nhất trong "
  "danh sách là coordinator; một thông điệp COORDINATOR đi thêm một vòng để công bố. Chi phí 2N thông điệp "
  "cho một người khởi xướng; biến thể Chang–Roberts có trung bình O(N log N), xấu nhất O(N²).")
figure(FIG["ring"], "Bầu chọn trên vòng: P6 hỏng, P2 khởi xướng, kết quả là P5", width=7.5)

h("3.4. So sánh và các vấn đề thực tế", 2)
table(["Tiêu chí", "Bully", "Ring"], [
    ["Giả thiết mạng", "Đồng bộ (timeout), đồ thị đầy đủ", "Vòng logic, cần biết tiến trình kế tiếp"],
    ["Số thông điệp", "O(N) – O(N²)", "2N – O(N²)"],
    ["Thời gian", "O(1) vòng timeout nếu gửi song song", "O(N) bước tuần tự"],
    ["Kết quả", "ID lớn nhất còn sống", "ID lớn nhất còn sống"],
    ["Phù hợp", "Cụm nhỏ, LAN, độ trễ thấp", "Mạng có cấu trúc vòng"],
], "So sánh Bully và Ring", widths=[3.5, 6.5, 6])
p("Trong thực tế, **phát hiện lỗi bằng timeout không hoàn hảo**: một coordinator bị treo tạm thời (GC pause, "
  "mạng nghẽn) có thể bị coi là chết, cụm bầu coordinator mới, rồi coordinator cũ “sống lại” và vẫn tưởng mình "
  "là coordinator. Hai kỹ thuật kinh điển để loại trừ tình huống này:")
bullets([
    "**Lease** (Gray & Cheriton, 1989): quyền chỉ có hiệu lực trong một khoảng thời gian và phải được gia hạn. Coordinator không gia hạn kịp thì tự mất quyền.",
    "**Fencing token** (Kleppmann): mỗi lần trao quyền cấp một số tăng đơn điệu; tài nguyên từ chối mọi yêu cầu mang số cũ. Đồ án dùng `generation` của Worker làm fencing token.",
])
p("Các hệ thống công nghiệp (ZooKeeper/ZAB, Raft, Paxos) giải bài toán mạnh hơn – đồng thuận với đa số (quorum) "
  "– để chịu được phân vùng mạng. Bully không dùng quorum; đồ án bù lại bằng việc dùng Worker làm trọng tài "
  "lease duy nhất (xem mục 6.4 về giới hạn).")
page_break()

# =============================================================== CHƯƠNG 4
h("CHƯƠNG 4. PHÂN TÍCH VÀ THIẾT KẾ HỆ THỐNG", 1)
h("4.1. Yêu cầu", 2)
table(["Mã", "Yêu cầu", "Loại"], [
    ["R1", "Các server tự tìm thấy nhau trong LAN và bầu Coordinator bằng Bully", "Chức năng"],
    ["R2", "Coordinator chết → cụm bầu Coordinator mới là ID lớn nhất còn sống", "Chịu lỗi"],
    ["R3", "Node ID cao khởi động lại → giành lại vai trò Coordinator", "Bully"],
    ["R4", "Client xin / hủy / trả quyền; chờ theo thứ tự FIFO", "Chức năng"],
    ["R5", "Tại mọi thời điểm tối đa một client giữ quyền dùng Worker", "An toàn (ME1)"],
    ["R6", "Client đang giữ quyền không mất quyền khi Coordinator đổi", "Chịu lỗi"],
    ["R7", "Client chết không chặn hàng đợi vĩnh viễn", "Sống (ME2)"],
    ["R8", "Triển khai trên LAN 192.168.0.0/24, mỗi node một máy", "Triển khai"],
], "Yêu cầu hệ thống", widths=[1.3, 11, 3.5])

h("4.2. Kiến trúc tổng thể", 2)
p("Hệ thống gồm ba ứng dụng Java độc lập (ba project Maven riêng, không phụ thuộc mã nguồn của nhau, chỉ chia "
  "sẻ hợp đồng giao thức `document.proto`):")
table(["Thành phần", "Vai trò", "Giao tiếp"], [
    ["server-node (×5)", "Discovery, bầu chọn Bully, heartbeat; khi là Coordinator thì quản lý hàng đợi yêu cầu", "UDP multicast :8999; TCP JSON :9000; gRPC tới Worker"],
    ["client-node (×5)", "Tìm Coordinator, xin/hủy/trả quyền, gửi tác vụ PDF", "TCP tới server; gRPC tới Worker"],
    ["document-worker (×1)", "Tài nguyên găng; trọng tài lease/generation; giữ session/token; xử lý PDF", "gRPC :9100"],
], "Các thành phần", widths=[3.5, 7.5, 5])
figure(FIG["topology"], "Topo triển khai trên LAN 192.168.0.0/24")
p("Nguyên tắc thiết kế cốt lõi là **tách hai câu hỏi**: Bully trả lời “server nào được quyền điều phối?”, còn "
  "Worker trả lời “client nào đang sở hữu tài nguyên?”. Coordinator chỉ là người xếp hàng và chuyển lời; "
  "quyết định cuối cùng về sở hữu luôn được tuần tự hóa tại Worker (phương thức `synchronized` của `WorkerState`).")

h("4.3. Giao thức", 2)
table(["Kết nối", "Giao thức", "Thông điệp"], [
    ["Server ↔ Server", "UDP multicast 239.255.42.99:8999", "SERVER_HELLO {clusterId, nodeId, tcpPort, bootId}"],
    ["Server ↔ Server", "TCP JSON :9000", "ELECTION, ELECTION_OK, COORDINATOR, HEARTBEAT, HEARTBEAT_ACK, GET_COORDINATOR, COORDINATOR_INFO, ELECTION_IN_PROGRESS"],
    ["Client ↔ Server", "TCP JSON :9000", "GET_COORDINATOR, ACCESS_REQUEST, ACCESS_CANCEL, ACCESS_RELEASE → ACCESS_QUEUED, ACCESS_GRANTED, ACCESS_STATUS"],
    ["Server → Worker", "gRPC :9100", "Control: CLAIM, RENEW, DROP, STATUS, RESERVE, RELEASE; GetWorkerStatus"],
    ["Client → Worker", "gRPC :9100", "GetWorkerStatus, MergePdf, SplitPdf, AddWatermark (kèm session/token)"],
], "Các kênh và thông điệp", widths=[3, 4.5, 8.5])
p("Mỗi thông điệp TCP là một dòng JSON UTF-8 (tối đa 64 KB), mỗi kết nối thực hiện đúng một cặp "
  "request/response. Ví dụ:")
code('{"type":"ELECTION","requestId":"6f1c…","senderId":"3","payload":{}}\n'
     '{"type":"COORDINATOR_INFO","requestId":"…","senderId":"2","payload":{"id":5,"host":"192.168.0.105","port":9000,"generation":7,"eligible":true}}')

h("4.4. Khám phá thành viên (discovery)", 2)
p("Đề bài Bully giả thiết mỗi tiến trình biết danh sách các tiến trình khác. Thay vì cấu hình tĩnh, mỗi server "
  "phát `SERVER_HELLO` mỗi giây tới nhóm multicast. Bên nhận lấy **IP từ địa chỉ nguồn của gói UDP** (không tin "
  "IP trong payload) và lưu bảng thành viên `ID → (IP, port, bootId, lastSeen)`. Thành viên không phát tin quá "
  "`discovery-timeout-ms` (5 s) bị loại. `bootId` (UUID mỗi lần chạy) giúp phát hiện hai tiến trình dùng cùng "
  "một ID: node phát hiện trùng chuyển sang trạng thái `ID_CONFLICT` và không tham gia bầu chọn – vì Bully chỉ "
  "đúng khi ID là duy nhất. Trước lần bầu đầu tiên, node chờ `discovery-warmup-ms` (2,5 s) để học đủ thành viên. "
  "TTL multicast = 1 nên gói không ra khỏi subnet `192.168.0.0/24`.")

h("4.5. Hiện thực giải thuật Bully", 2)
p("Lớp `ServerNode` hiện thực Bully với các quy tắc sau (đối chiếu với giả mã ở mục 3.2):")
numbered([
    "**Phát hiện lỗi:** follower gửi `HEARTBEAT` tới Coordinator mỗi `heartbeat-ms` (1 s), timeout 1 s. Sau `heartbeat-failures` (3) lần lỗi liên tiếp, follower xóa Coordinator và gọi `startElection()`.",
    "**ELECTION:** gửi **song song** tới mọi thành viên có ID lớn hơn (`broadcast`), nên một máy chết chỉ tốn một lần timeout thay vì một timeout cho mỗi máy.",
    "**ANSWER:** node nhận ELECTION từ ID nhỏ hơn chỉ trả `ELECTION_OK` khi nó **đủ điều kiện tiếp quản** (`eligible()`: đã hết warm-up, không trùng ID, liên lạc được Worker), rồi tự khởi động bầu chọn. Nếu không đủ điều kiện, nó trả `ELECTION_IN_PROGRESS` để node nhỏ hơn tiếp tục ứng cử – tránh tình huống “hứa tiếp quản nhưng không ai tiếp quản”.",
    "**Thắng cử:** không nhận được `ELECTION_OK` nào → chờ `election-ms` (2 s) rồi `CLAIM` lease tại Worker. Chỉ khi Worker chấp nhận (trả generation mới) node mới đặt `coordinator = true` và gửi `COORDINATOR` song song tới mọi thành viên.",
    "**Nhận COORDINATOR:** node kiểm chứng với Worker rằng ID đó đang giữ lease hợp lệ rồi mới ghi nhận – chống thông điệp COORDINATOR cũ/giả.",
    "**Đã nhận ANSWER nhưng không có COORDINATOR:** sau `election-ms`, nếu vẫn chưa có Coordinator thì chu kỳ kế tiếp tự bầu lại (đúng quy tắc T' của Bully).",
    "**Node ID cao phục hồi:** Coordinator hiện tại định kỳ (2 s) hỏi `GET_COORDINATOR` các thành viên; nếu thấy một node ID lớn hơn **đủ điều kiện**, nó khởi động bầu chọn, nhận `ELECTION_OK`, `DROP` lease và lùi về STANDBY; node ID cao `CLAIM` lease và công bố. Đây chính là bước “bully”.",
    "**Coordinator nhận ELECTION từ node nhỏ hơn** (ví dụ node nhỏ vừa khởi động lại): trả `ELECTION_OK` và **công bố lại** `COORDINATOR`, nhờ đó node nhỏ hội tụ ngay thay vì bầu lại vô hạn.",
])
figure(FIG["bully"], "Trình tự Bully khi Coordinator S5 hỏng (S4 thắng)")
figure(FIG["state"], "Máy trạng thái của một server-node")

h("4.5.1. Lease và fencing", 3)
p("Worker giữ ba biến: `coordinator`, `generation`, `leaseUntil`. `CLAIM` thành công khi lease cũ đã hết hạn "
  "(ID khác hay chính ID đó đều phải chờ – chống process trùng ID cướp lease), khi đó `generation++` và `leaseUntil = now + lease-ms` (5 s). Coordinator gia hạn "
  "bằng `RENEW` mỗi giây. Mọi lệnh khác ngoài CLAIM phải mang đúng `(coordinatorId, generation)` và lease còn hạn, "
  "nếu không bị từ chối với lỗi “Stale coordinator or expired lease”. Hệ quả:")
bullets([
    "Coordinator cũ bị treo rồi sống lại không thể cấp quyền, vì generation của nó đã cũ.",
    "Node mới chỉ trở thành Coordinator sau khi lease cũ hết hạn hoặc bị nhường (DROP), nên **không bao giờ có hai Coordinator cùng giữ lease hợp lệ**.",
    "Đồng hồ dùng để tính lease là đồng hồ đơn điệu của riêng Worker (`System.nanoTime`) – không cần đồng bộ đồng hồ giữa các máy.",
])

h("4.6. Loại trừ tương hỗ giữa các client", 2)
figure(FIG["mutex"], "Luồng xin – dùng – trả quyền", width=14.5)
p("Client sinh một `requestId` (UUID) cho mỗi lần xin quyền và **giữ nguyên** nó cho tới khi được cấp, hủy hoặc "
  "trả. Client gửi lặp lại `ACCESS_REQUEST` mỗi 700 ms (vừa là poll vị trí hàng đợi, vừa là tín hiệu còn sống). "
  "Coordinator xếp yêu cầu vào hàng đợi FIFO (`LinkedHashMap`) và gọi `pump()`: lấy yêu cầu đầu hàng, gọi "
  "`RESERVE` tại Worker. Worker chỉ tạo session khi không có session nào đang hoạt động; session gồm `sessionId` và "
  "`accessToken` ngẫu nhiên 256 bit. Client dùng session/token gọi trực tiếp Worker để xử lý PDF; xong thì gửi "
  "`ACCESS_RELEASE`.")
p("`RESERVE` là **idempotent** theo cặp `(clientId, requestId)`: gọi lại với cùng cặp trả về đúng session/token "
  "cũ thay vì tạo session mới. Đây là chìa khóa để xử lý trường hợp phản hồi bị mất giữa chừng khi failover.")
h("4.6.1. Các kịch bản lỗi", 3)
table(["Tình huống", "Cách hệ thống xử lý"], [
    ["Coordinator chết trước khi nhận yêu cầu", "Client mất kết nối, tìm Coordinator mới, gửi lại cùng requestId"],
    ["Yêu cầu đang trong hàng đợi thì Coordinator chết", "Hàng đợi RAM mất; client gửi lại và được xếp hàng ở Coordinator mới"],
    ["Worker đã RESERVE nhưng phản hồi bị mất", "Coordinator mới gọi RESERVE cùng (clientId, requestId) → nhận lại đúng session cũ"],
    ["Client đang giữ quyền khi Coordinator chết", "Session nằm ở Worker nên vẫn hợp lệ; client tiếp tục dùng và gửi RELEASE tới Coordinator mới"],
    ["Client đang chờ thì chết", "Không poll quá `queue-timeout-ms` (3 s) → bị loại khỏi hàng đợi"],
    ["Được cấp nhưng client chết trước khi nhận token", "Coordinator thu hồi session chưa dùng sau 3 s"],
    ["Client giữ quyền rồi chết", "Session idle quá `session-ms` (60 s) → Worker thu hồi, cấp cho người kế tiếp"],
    ["Coordinator cũ bị treo rồi chạy lại", "Generation cũ bị Worker từ chối; nó tự hạ cấp"],
], "Xử lý lỗi trong loại trừ tương hỗ", widths=[6, 10])

h("4.7. Lập luận về tính đúng", 2)
p("**Định lý 1 (an toàn của loại trừ tương hỗ).** Tại mọi thời điểm có tối đa một session hoạt động. "
  "*Lập luận:* mọi thay đổi session diễn ra trong các phương thức `synchronized` của `WorkerState`; `RESERVE` chỉ "
  "tạo session mới khi `session` rỗng; xử lý PDF (`begin`) yêu cầu đúng bộ ba (clientId, sessionId, token). Vì "
  "Worker là một tiến trình duy nhất, tính chất này không phụ thuộc số Coordinator hay thứ tự thông điệp mạng.")
p("**Định lý 2 (tối đa một Coordinator có hiệu lực).** Tại mọi thời điểm có tối đa một node mà Worker chấp nhận "
  "các lệnh điều phối. *Lập luận:* Worker chỉ có một cặp (coordinator, generation) hiện hành; CLAIM của node "
  "khác chỉ thành công khi lease hết hạn và làm tăng generation, từ đó mọi lệnh mang generation cũ bị từ chối.")
p("**Định lý 3 (sống của bầu chọn – trong giả thiết đồng bộ).** Nếu từ một thời điểm nào đó tập node còn sống "
  "không đổi, mạng không phân vùng và node có ID lớn nhất M liên lạc được Worker, thì sau hữu hạn thời gian M là "
  "Coordinator. *Lập luận:* M không nhận `ELECTION_OK` từ ai (không có ID lớn hơn còn sống), nên mỗi vòng bầu của M "
  "đi tới CLAIM; nếu một node L < M đang giữ lease thì L phát hiện M qua probe định kỳ, gửi ELECTION, nhận OK và "
  "DROP lease; lease của node chết tự hết hạn sau tối đa `lease-ms`. Khi đó CLAIM của M thành công.")
p("**Định lý 4 (sống của loại trừ tương hỗ).** Mọi client còn sống và tiếp tục poll cuối cùng được cấp quyền, "
  "với điều kiện mỗi client giữ quyền đều trả quyền hoặc bị thu hồi sau `session-ms`. *Lập luận:* hàng đợi FIFO "
  "hữu hạn; session luôn kết thúc (release hoặc timeout); khi đó `pump()` cấp cho yêu cầu đầu hàng.")
page_break()

# =============================================================== CHƯƠNG 5
h("CHƯƠNG 5. CÀI ĐẶT VÀ TRIỂN KHAI TRÊN MẠNG LAN", 1)
h("5.1. Công nghệ", 2)
table(["Thành phần", "Công nghệ"], [
    ["Ngôn ngữ, nền tảng", "Java 21 (virtual threads), Maven"],
    ["Giao diện", "JavaFX 21 (có chế độ `--headless`)"],
    ["Truyền thông", "Socket TCP + Jackson (JSON), MulticastSocket (UDP), gRPC 1.71 + Protocol Buffers"],
    ["Xử lý PDF", "Apache PDFBox"],
    ["Kiểm thử", "JUnit 5; integration test khởi chạy nhiều JVM thật"],
], "Công nghệ sử dụng", widths=[5, 11])

h("5.2. Cấu trúc mã nguồn", 2)
code("""
Bully-Algorithm/
├── server-node/     ServerNode.java (Bully, heartbeat, hàng đợi), support/ServerDiscovery.java
├── client-node/     ClientNode.java (tìm Coordinator, xin/trả quyền), ClientView.java
├── document-worker/ WorkerState.java (lease, generation, session), PdfService.java
├── run/             run-server.cmd, run-client.cmd, run-worker.cmd, setup-firewall.cmd
├── scripts/         build.ps1, start-demo.ps1, check-protocol.ps1
└── docs/            PROTOCOL.md, TESTING.md, báo cáo này
""")

h("5.3. Tham số cấu hình và phân tích thời gian", 2)
table(["Tham số", "Giá trị", "Ý nghĩa"], [
    ["heartbeat-ms", "1000", "Chu kỳ heartbeat / gia hạn lease"],
    ["heartbeat-timeout-ms", "1000", "Timeout kết nối và đọc cho thông điệp server–server"],
    ["heartbeat-failures", "3", "Số lần lỗi liên tiếp để kết luận Coordinator chết"],
    ["election-ms", "2000", "Thời gian chờ trước khi CLAIM (T' của Bully)"],
    ["lease-ms (Worker)", "5000", "Thời hạn lease Coordinator"],
    ["discovery-interval / timeout / warmup", "1000 / 5000 / 2500", "Chu kỳ HELLO, hạn thành viên, thời gian học thành viên"],
    ["queue-timeout-ms", "3000", "Loại client không poll; thu hồi grant chưa nhận"],
    ["session-ms (Worker)", "60000", "Thu hồi session idle"],
], "Tham số chính", widths=[5.5, 3.5, 7])
p("Ước lượng thời gian failover khi Coordinator crash: phát hiện lỗi mất 3–6 s (3 heartbeat lỗi, mỗi lần tối đa "
  "1 s timeout cộng 1 s chu kỳ); pha ELECTION song song mất tối đa khoảng 2 s; chờ `election-ms` 2 s; CLAIM chỉ thành "
  "công khi lease của Coordinator cũ hết hạn – tối đa 5 s kể từ lần RENEW cuối, phần lớn đã trôi qua trong lúc phát "
  "hiện lỗi. Tổng cộng khoảng **5–10 s**. Đây là ước lượng theo tham số; thời gian đo thực tế phụ thuộc máy và mạng.")
p("Ràng buộc quan trọng: **khoảng cách giữa hai lần RENEW phải nhỏ hơn lease-ms**. Vì vậy luồng gia hạn lease "
  "không được phép bị chặn bởi các lời gọi mạng chậm (xem lỗi L2 ở mục 6.3).")

h("5.4. Quy hoạch mạng LAN 192.168.0.0/24", 2)
table(["Máy", "IP", "Tiến trình", "Cổng mở (inbound)", "Lệnh chạy"], [
    ["Worker", "192.168.0.100", "document-worker", "TCP 9100", "`run\\run-worker.cmd`"],
    ["Node 1", "192.168.0.101", "server ID 1 + client-1", "TCP 9000, UDP 8999", "`run\\run-server.cmd 1`, `run\\run-client.cmd 1`"],
    ["Node 2", "192.168.0.102", "server ID 2 + client-2", "TCP 9000, UDP 8999", "`run\\run-server.cmd 2`, `run\\run-client.cmd 2`"],
    ["Node 3", "192.168.0.103", "server ID 3 + client-3", "TCP 9000, UDP 8999", "`run\\run-server.cmd 3`, `run\\run-client.cmd 3`"],
    ["Node 4", "192.168.0.104", "server ID 4 + client-4", "TCP 9000, UDP 8999", "`run\\run-server.cmd 4`, `run\\run-client.cmd 4`"],
    ["Node 5", "192.168.0.105", "server ID 5 + client-5", "TCP 9000, UDP 8999", "`run\\run-server.cmd 5`, `run\\run-client.cmd 5`"],
], "Quy hoạch IP và dịch vụ", widths=[1.8, 2.8, 3.6, 3.2, 4.6])
p("Các bước triển khai:")
numbered([
    "Cấu hình **DHCP reservation** (gán IP cố định theo MAC) trên router cho 6 máy. Lưu ý nhiều router đặt dải DHCP động bắt đầu từ .100, nên phải reservation chứ không chỉ “hy vọng” máy nhận đúng IP.",
    "Đặt network profile của Windows là **Private**; chạy `run\\setup-firewall.cmd worker` trên máy Worker và `run\\setup-firewall.cmd server` trên 5 máy node (quyền Administrator). Rule chỉ mở cho nguồn `192.168.0.0/24`. Script cảnh báo nếu còn rule **Block** cho java.exe (Windows tự tạo khi người dùng bấm Cancel ở hộp thoại firewall; rule Block luôn thắng rule Allow).",
    "Build một lần bằng `scripts\\build.ps1` rồi chép thư mục dự án (có `target/*.jar`) sang các máy; mỗi máy cần Java 21 trở lên trong PATH (script kiểm tra phiên bản).",
    "Chạy Worker trước, sau đó 5 server, cuối cùng các client. Nếu không truyền tham số, `run-server.cmd` / `run-client.cmd` tự suy ra ID từ IP `192.168.0.10x` của máy.",
])
p("Script `run-server.cmd` kiểm tra ID thuộc 1..5 và máy thực sự có IP `192.168.0.10<ID>` trước khi khởi chạy, "
  "vì `bind-address` trong `server-<ID>.properties` là IP đó; chạy nhầm ID trên máy khác sẽ bị báo lỗi rõ ràng "
  "thay vì một ngoại lệ Java khó hiểu.")

h("5.5. Đánh giá cấu hình LAN hiện tại", 2)
table(["Hạng mục", "Đánh giá"], [
    ["bind-address = IP LAN của từng server", "Đúng. Bắt buộc để MulticastSocket gắn đúng card mạng khi máy có nhiều card (VPN, Hyper-V, WSL, Docker…) và để địa chỉ công bố cho node khác là IP thật."],
    ["Cùng port 9000 cho mọi server", "Hợp lý vì mỗi server một IP; chạy chung một máy thì phải đổi port (mục 12 README)."],
    ["Multicast TTL=1, nhóm 239.255.42.99", "Hợp lý: phạm vi tổ chức (administratively scoped), không ra khỏi subnet. Wi-Fi có “client isolation” hoặc switch chặn multicast sẽ làm discovery thất bại → khi đó thêm `servers=` làm seed."],
    ["Client: bind 0.0.0.0, local-port 0", "Đúng: client không lắng nghe nên không xung đột với server cùng máy."],
    ["Worker bind 0.0.0.0:9100", "Chấp nhận được trong LAN tin cậy; firewall giới hạn nguồn 192.168.0.0/24."],
    ["Timeout 1 s, 3 lần lỗi, lease 5 s", "Phù hợp LAN có dây (RTT < 1 ms). Với Wi-Fi chập chờn nên tăng heartbeat-failures lên 4–5 và lease-ms tương ứng."],
    ["Không TLS, không xác thực", "Chấp nhận trong phạm vi đồ án (LAN tin cậy); không được dùng trên mạng không tin cậy."],
], "Đánh giá cấu hình triển khai LAN", widths=[5, 11])
page_break()

# =============================================================== CHƯƠNG 6
h("CHƯƠNG 6. KIỂM THỬ VÀ ĐÁNH GIÁ", 1)
h("6.1. Kiểm thử tự động", 2)
p("Kiểm thử chạy bằng `scripts\\build.ps1 -Integration`. Integration test khởi chạy các JVM thật "
  "(1 Worker + 3 server + các client giả lập) trên localhost với tham số thời gian rút ngắn và kill tiến trình "
  "thật để mô phỏng crash.")
table(["Bộ test", "Số test", "Nội dung"], [
    ["server-node ConfigTest", "3", "Đọc cấu hình UTF-8, ghi đè bằng tham số dòng lệnh, kiểm tra bind/port"],
    ["client-node ConfigTest", "3", "Như trên cho client"],
    ["document-worker ConfigTest", "3", "Như trên cho Worker"],
    ["WorkerStateTest", "11", "Lease, generation, fencing, ID trùng không cướp lease, token, timeout, yêu cầu đồng thời"],
    ["ClientNodeTest", "2", "Client theo generation lớn nhất; reply hỏng không làm client ngừng"],
    ["PdfServiceTest", "5", "Merge, Split, Watermark và giới hạn kích thước"],
    ["DistributedIntegrationTest", "9", "FIFO + failover + restart; yêu cầu đồng thời; Coordinator chết khi có 3 yêu cầu; client phục hồi; node thấp restart; node cao mất Worker; client chờ bị ngắt; node đang warm-up không trả ELECTION_OK; 10 peer không phản hồi không làm mất lease"],
    ["test-run-scripts.ps1", "45 case", "Các file run\\*.cmd: ID/IP, tham số lách luật, phiên bản Java, đường dẫn có “!”, kèm mutation"],
    ["*ViewTest (JavaFX)", "3", "Mở giao diện; chỉ chạy với cờ `-Ui` (cần màn hình)"],
], "Các bộ kiểm thử", widths=[4.5, 1.7, 9.8])
p("Kết quả lần chạy ngày 23/11/2026 trên nhánh sửa lỗi: **36/36 test Java không cần giao diện đạt** (0 failure, "
  "0 error), gồm 9 integration test đa JVM; 3 test giao diện được bỏ qua do chạy không có màn hình; 42/45 case "
  "kiểm thử script. Mỗi guard mới đều có mutation test: cố ý làm hỏng guard thì test tương ứng phải thất bại.")

h("6.2. Kịch bản kiểm thử thủ công trên LAN", 2)
table(["#", "Thao tác", "Kết quả mong đợi"], [
    ["1", "Chạy Worker và 5 server", "Sau ~2,5 s warm-up, Server 5 là COORDINATOR; Worker log generation=1"],
    ["2", "5 client bấm Request Access", "Đúng 1 client ACTIVE, 4 client QUEUED với vị trí 1..4"],
    ["3", "Dừng Server 5", "Follower log heartbeat timeout ×3, ELECTION, ELECTION_OK; Server 4 CLAIM, COORDINATOR"],
    ["4", "Client đang ACTIVE xử lý PDF", "Vẫn thành công với session cũ"],
    ["5", "Release", "Client kế tiếp ACTIVE"],
    ["6", "Chạy lại Server 5", "Server 4 thấy ID 5 đủ điều kiện → ELECTION → DROP; Server 5 trở lại COORDINATOR"],
    ["7", "Tắt client đang chờ / đang giữ quyền", "Bị loại sau 3 s / thu hồi sau 60 s"],
    ["8", "Rút cáp mạng (không chỉ tắt tiến trình) máy Coordinator", "Như bước 3; các follower không bị kéo dài chu kỳ (nhờ gửi song song)"],
], "Kịch bản demo", widths=[0.8, 6.2, 9])

h("6.3. Rà soát mã nguồn và các lỗi đã sửa", 2)
p("Quá trình rà soát tập trung vào giải thuật Bully giữa client và server-node. Các lỗi sau đã được phát hiện và sửa:")
table(["Mã", "Lỗi", "Hậu quả", "Cách sửa"], [
    ["L1", "Node trả `ELECTION_OK` ngay cả khi đang warm-up (chưa thể tự ứng cử)",
     "Coordinator hiện tại DROP lease khi node ID cao vừa khởi động, nhưng node đó chưa bầu được → cụm không có Coordinator thêm vài giây. Vi phạm ngữ nghĩa ANSWER của Bully",
     "`eligible()` = hết warm-up ∧ không trùng ID ∧ liên lạc được Worker; chỉ trả OK khi eligible"],
    ["L2", "Thăm dò peer (GET_COORDINATOR) và COORDINATOR được gửi **tuần tự** trong cùng luồng gia hạn lease",
     "Trong LAN thật, máy tắt nguồn không trả RST nên mỗi lời gọi chờ đủ timeout 1 s; vài máy chết làm chu kỳ RENEW vượt 5 s → Coordinator đang sống **tự mất lease**",
     "Tách probe sang luồng riêng; ELECTION/COORDINATOR/probe gửi song song – tổng thời gian bị chặn bởi một timeout"],
    ["L3", "Coordinator khởi động bầu chọn mỗi 2 s với mọi node ID cao hơn, kể cả node không đủ điều kiện",
     "Log bầu chọn liên tục, tốn thông điệp",
     "Phản hồi kèm cờ `eligible`; chỉ bầu khi node cao hơn đủ điều kiện"],
    ["L4", "Client chọn câu trả lời `COORDINATOR_INFO` **đầu tiên** theo thứ tự server 1→5",
     "Trong lúc failover, server 1 còn nhớ Coordinator đã chết → client bị chỉ tới node chết lặp đi lặp lại",
     "Hỏi song song mọi server, chọn câu trả lời có **generation lớn nhất** (generation do Worker cấp, tăng đơn điệu)"],
    ["L5", "Một lần RENEW lỗi tạm thời (timeout gRPC) làm Coordinator hạ cấp ngay dù lease vẫn còn hạn",
     "Không ai CLAIM được lease còn hạn → cụm không có Coordinator tới 5 s",
     "Server giữ hạn lease cục bộ (đo từ lúc gửi nên không muộn hơn Worker), chỉ hạ cấp khi đã quá hạn. Phương án đầu (cho chủ lease CLAIM lại ở Worker) bị loại khi review vì process trùng ID có thể cướp lease"],
    ["L6", "Biến `failures` được ghi từ nhiều luồng không đồng bộ", "Data race (nhỏ)", "Khai báo `volatile`"],
    ["L7", "File `.cmd` mặc định ID = 1, kết thúc dòng LF", "Chạy nhầm ID trên máy khác; cmd.exe có thể nhảy nhãn sai với LF",
     "Tự nhận ID theo IP, kiểm tra IP, `.gitattributes` ép CRLF"],
    ["L8", "README và TESTING.md ghi 56 test và 5 lớp test không tồn tại; thực tế 34", "Tài liệu sai sự thật", "Cập nhật bảng test đúng thực tế"],
    ["L9", "`run-server.cmd` không tham số báo “The syntax of the command is incorrect”", "Kịch bản tự nhận ID – cách dùng chính – hỏng hoàn toàn", "Biến luôn có giá trị trước khi lấy chuỗi con; test bắt mọi lỗi cú pháp cmd"],
    ["L10", "Tham số thứ 2 trở đi được chuyển thẳng cho Java", "`run-server.cmd 3 --id 5` lách kiểm tra ID/IP", "Chỉ chấp nhận `--headless`"],
    ["L11", "`EnableDelayedExpansion` trong script", "Thư mục có dấu `!` → không tìm thấy JAR", "Bỏ delayed expansion; test chạy trong thư mục có `!`"],
    ["L12", "Script firewall báo thành công giả; không phát hiện rule Block cho java.exe", "Cổng vẫn đóng dù script báo “Đã mở”", "`$ErrorActionPreference='Stop'`; cảnh báo rule Block (chính máy dev có 4 rule như vậy)"],
    ["L13", "Ngoại lệ trong `discover()` của client thoát khỏi tác vụ định kỳ", "ScheduledExecutorService âm thầm hủy tác vụ → client “đóng băng” vĩnh viễn", "Bắt lỗi theo từng server và bao toàn bộ tick"],
    ["L14", "`start-demo.ps1` dùng config LAN để chạy 11 tiến trình trên một máy", "Cả 5 server chết ngay vì không có IP 192.168.0.10x", "Ghi đè sang 127.0.0.1:9001–9005; báo lỗi nếu tiến trình chết khi khởi động"],
], "Các lỗi phát hiện và đã sửa", widths=[1.4, 3.9, 5.4, 5.3])

h("6.4. Giới hạn", 2)
bullets([
    "**Worker là điểm lỗi đơn** và giữ trạng thái trong RAM; Worker chết thì không cấp quyền được. Đây là đánh đổi có chủ đích: Worker vừa là tài nguyên găng vừa là trọng tài lease.",
    "**Phân vùng mạng:** nếu Coordinator vẫn thấy Worker nhưng bị tách khỏi các follower, follower không CLAIM được (lease bị giữ) – an toàn nhưng client phía bên kia phân vùng phải chờ. Bully không dùng quorum nên không giải quyết được trọn vẹn; muốn vậy cần Raft/Paxos.",
    "Hàng đợi của Coordinator không được nhân bản; sau failover thứ tự FIFO là thứ tự client gửi lại.",
    "Heartbeat không phân biệt tiến trình chết với mạng chậm (giới hạn chung của mọi bộ phát hiện lỗi dựa trên timeout – hệ quả của FLP).",
    "Client chưa tự discovery, cần danh sách server tĩnh; không có TLS/xác thực.",
])
page_break()

# =============================================================== KẾT LUẬN
h("KẾT LUẬN VÀ HƯỚNG PHÁT TRIỂN", 1)
p("Đồ án đã trình bày cơ sở lý thuyết của hệ thống phân tán, các giải thuật loại trừ tương hỗ (tập trung, Lamport, "
  "Ricart–Agrawala, Maekawa, token) và các giải thuật bầu chọn (Bully, Ring); từ đó lựa chọn tổ hợp **loại trừ "
  "tương hỗ tập trung + bầu chọn Bully** và hiện thực thành hệ thống chạy thật trên LAN 5 node.")
p("Kết quả đạt được: các server tự khám phá nhau, bầu đúng node ID lớn nhất làm Coordinator, tự bầu lại khi "
  "Coordinator hỏng và trả lại vai trò khi node ID cao phục hồi; client được cấp quyền FIFO, tối đa một client giữ "
  "tài nguyên tại một thời điểm, kể cả trong lúc chuyển Coordinator nhờ lease và fencing token. Quá trình rà soát đã "
  "phát hiện và sửa các lỗi ảnh hưởng trực tiếp tới ngữ nghĩa Bully và độ ổn định trên mạng LAN thật.")
p("Hướng phát triển:")
bullets([
    "Nhân bản hàng đợi yêu cầu sang các follower để giữ thứ tự FIFO sau failover.",
    "Thay Worker-trọng-tài bằng đồng thuận đa số (Raft) để chịu được phân vùng mạng và loại bỏ điểm lỗi đơn.",
    "Client tự discovery Coordinator qua multicast; thêm TLS và xác thực giữa các thành phần.",
    "Đo đạc thời gian failover thực tế trên nhiều cấu hình mạng (có dây, Wi-Fi) và tối ưu tham số.",
])

h("TÀI LIỆU THAM KHẢO", 1)
refs = [
    "A. S. Tanenbaum, M. van Steen. *Distributed Systems*, 4th ed., 2023 – Chương 5 (Coordination).",
    "G. Coulouris, J. Dollimore, T. Kindberg, G. Blair. *Distributed Systems: Concepts and Design*, 5th ed., Addison-Wesley, 2011 – Chương 15.",
    "H. Garcia-Molina. “Elections in a Distributed Computing System.” *IEEE Transactions on Computers*, C-31(1), 1982.",
    "L. Lamport. “Time, Clocks, and the Ordering of Events in a Distributed System.” *Communications of the ACM*, 21(7), 1978.",
    "G. Ricart, A. K. Agrawala. “An Optimal Algorithm for Mutual Exclusion in Computer Networks.” *Communications of the ACM*, 24(1), 1981.",
    "M. Maekawa. “A √N Algorithm for Mutual Exclusion in Decentralized Systems.” *ACM TOCS*, 3(2), 1985.",
    "I. Suzuki, T. Kasami. “A Distributed Mutual Exclusion Algorithm.” *ACM TOCS*, 3(4), 1985.",
    "E. Chang, R. Roberts. “An Improved Algorithm for Decentralized Extrema-Finding in Circular Configurations of Processes.” *Communications of the ACM*, 22(5), 1979.",
    "M. J. Fischer, N. A. Lynch, M. S. Paterson. “Impossibility of Distributed Consensus with One Faulty Process.” *Journal of the ACM*, 32(2), 1985.",
    "C. Gray, D. Cheriton. “Leases: An Efficient Fault-Tolerant Mechanism for Distributed File Cache Consistency.” *SOSP*, 1989.",
    "M. Kleppmann. *Designing Data-Intensive Applications*, O’Reilly, 2017 – Chương 8 (fencing tokens).",
    "D. Ongaro, J. Ousterhout. “In Search of an Understandable Consensus Algorithm (Raft).” *USENIX ATC*, 2014.",
]
for i, ref in enumerate(refs, 1):
    para = doc.add_paragraph()
    para.paragraph_format.left_indent = Cm(1.0)
    para.paragraph_format.first_line_indent = Cm(-1.0)
    import re
    para.add_run(f"[{i}]\t")
    for part in re.split(r"(\*[^*]+\*)", ref):
        if part.startswith("*"):
            para.add_run(part[1:-1]).italic = True
        elif part:
            para.add_run(part)

page_numbers()
doc.save(OUT)
print("Saved", os.path.abspath(OUT))
