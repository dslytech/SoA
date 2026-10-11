// Haegemonia-bik.mp4converter - convert your own game .bik videos to .bik.mp4
// Part of the SoA / SoA32to64 projects.  License: PolyForm Noncommercial 1.0.0
// (the bundled ffmpeg.exe is a separate program under the GPL, see NOTICE-ffmpeg.txt)

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace BikMp4Converter
{
    static class Program
    {
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        [STAThread]
        static void Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm(args));
        }
    }

    class MainForm : Form
    {
        // ---------- texts (EN / HU) ----------
        static readonly Dictionary<string, string[]> T = new Dictionary<string, string[]>
        {
            {"title",    new[]{"Haegemonia-bik.mp4converter  -  .bik to .bik.mp4", "Haegemonia-bik.mp4converter  -  .bik átalakítása .bik.mp4-re"}},
            {"lang",     new[]{"Language", "Nyelv"}},
            {"intro",    new[]{"Converts the Bink (.bik) videos of YOUR OWN copy of Haegemonia: Legions of Iron to .bik.mp4, and puts them into the right folder structure for SoA / SoA32to64.",
                               "A SAJÁT Haegemonia: Legions of Iron példányod Bink (.bik) videóit alakítja .bik.mp4-re, és az SoA / SoA32to64-hez való mappaszerkezetbe teszi."}},
            {"src",      new[]{"1. Folder with the .bik videos (subfolders are included; you can also drop files or folders on this window)",
                               "1. A .bik videókat tartalmazó mappa (az almappákat is beleveszi; ráhúzhatsz fájlokat vagy mappákat az ablakra is)"}},
            {"browse",   new[]{"Browse...", "Tallózás..."}},
            {"tgt",      new[]{"2. Which app is it for?", "2. Melyik apphoz készül?"}},
            {"tgtSoA",   new[]{"SoA  (CPU can run 32-bit apps)  ->  .../com.microids.google.HaegemoniaFull/videos/fmv",
                               "SoA  (a CPU futtat 32 bites appokat)  ->  .../com.microids.google.HaegemoniaFull/videos/fmv"}},
            {"tgtZB",    new[]{"SoA32to64  (64-bit-only CPU, ZettaBridge)  ->  .../com.zettabridge.launcher/hv/fmv",
                               "SoA32to64  (csak 64 bites CPU, ZettaBridge)  ->  .../com.zettabridge.launcher/hv/fmv"}},
            {"out",      new[]{"3. Output folder on this PC (the full Android/obb/... path is created inside it)",
                               "3. Kimeneti mappa ezen a gépen (benne létrejön a teljes Android/obb/... útvonal)"}},
            {"adb",      new[]{"Also copy to the connected phone/tablet with ADB (USB debugging must be on)",
                               "Másolás a csatlakoztatott telefonra/tabletre is ADB-vel (be kell kapcsolni az USB-hibakeresést)"}},
            {"adbNote",  new[]{"ADB: adb.exe next to this program or in PATH is used.",
                               "ADB: a program mellett vagy a PATH-ban lévő adb.exe-t használja."}},
            {"go",       new[]{"Convert", "Átalakítás"}},
            {"cancel",   new[]{"Cancel", "Mégse"}},
            {"open",     new[]{"Open output folder", "Kimeneti mappa megnyitása"}},
            {"about",    new[]{"About / License", "Névjegy / Licenc"}},
            {"close",    new[]{"Close", "Bezárás"}},
            {"nosrc",    new[]{"Please choose a folder with .bik files.", "Válassz ki egy mappát, amiben .bik fájlok vannak."}},
            {"nofiles",  new[]{"No .bik files found.", "Nem található .bik fájl."}},
            {"noout",    new[]{"Please choose an output folder.", "Válassz kimeneti mappát."}},
            {"found",    new[]{"{0} .bik file(s) found.", "{0} db .bik fájl található."}},
            {"conv",     new[]{"[{0}/{1}] converting: {2}", "[{0}/{1}] átalakítás: {2}"}},
            {"skip",     new[]{"[{0}/{1}] skipped (already exists): {2}", "[{0}/{1}] kihagyva (már létezik): {2}"}},
            {"fail",     new[]{"   FAILED: {0}", "   SIKERTELEN: {0}"}},
            {"done",     new[]{"Done.  Converted: {0}   Skipped: {1}   Failed: {2}", "Kész.  Átalakítva: {0}   Kihagyva: {1}   Sikertelen: {2}"}},
            {"cancelled",new[]{"Cancelled.", "Megszakítva."}},
            {"outis",    new[]{"Output: {0}", "Kimenet: {0}"}},
            {"copyhint", new[]{"Copy the folder  Android  from the output folder into the root of the device storage (merge if asked).",
                               "Az Android mappát a kimeneti mappából másold az eszköz tárhelyének gyökerébe (egyesítés, ha rákérdez)."}},
            {"noadb",    new[]{"adb.exe not found - copy the files to the device by hand.", "Az adb.exe nem található - másold át a fájlokat kézzel."}},
            {"adbstart", new[]{"ADB: copying to the device...", "ADB: másolás az eszközre..."}},
            {"adbok",    new[]{"ADB: copied to {0}", "ADB: átmásolva ide: {0}"}},
            {"adbfail",  new[]{"ADB: failed (device connected? USB debugging on? authorized?)", "ADB: sikertelen (csatlakoztatva az eszköz? be van kapcsolva az USB-hibakeresés? engedélyezve?)"}},
            {"ffmissing",new[]{"Internal error: the bundled ffmpeg could not be prepared.", "Belső hiba: a beépített ffmpeg nem készíthető elő."}},
            {"aboutbody",new[]{
@"Haegemonia-bik.mp4converter

Converts your own game .bik videos to .bik.mp4 for the SoA and SoA32to64 apps.

License of this program: PolyForm Noncommercial 1.0.0 - free to use, change and share, but NOT for commercial use. Copyright (c) 2026 the SoA authors.

Bundled third-party software:
 - FFmpeg 7.1.1 (https://ffmpeg.org) with x264 (https://www.videolan.org/developers/x264.html), compiled as a reduced build (Bink input, H.264 + AAC output, MP4). Licensed under the GNU GPL version 2 or later. It runs as a separate program; its source code is available at the links above and in the SoA repositories (see NOTICE-ffmpeg.txt).

Use only videos from your own copy of the game. No game files are included.",
@"Haegemonia-bik.mp4converter

A saját játékod .bik videóit alakítja .bik.mp4-re az SoA és SoA32to64 apphoz.

A program licence: PolyForm Noncommercial 1.0.0 - szabadon használható, módosítható és megosztható, de kereskedelmi célra NEM. Copyright (c) 2026 az SoA készítői.

Beépített harmadik féltől származó szoftver:
 - FFmpeg 7.1.1 (https://ffmpeg.org) x264-gyel (https://www.videolan.org/developers/x264.html), csökkentett változatban fordítva (Bink bemenet, H.264 + AAC kimenet, MP4). GNU GPL 2. vagy újabb verzió alatt. Külön programként fut; a forráskódja a fenti linkeken és az SoA tárolókban érhető el (lásd NOTICE-ffmpeg.txt).

Csak a saját játékpéldányod videóit használd. Játékfájlokat nem tartalmaz."}},
        };

        int lang;   // 0 = EN, 1 = HU
        string S(string key) { return T[key][lang]; }

        // ---------- controls ----------
        ComboBox cbLang;
        Label lblIntro, lblSrc, lblTgt, lblOut, lblAdbNote;
        TextBox tbSrc, tbOut, log;
        Button btnSrc, btnOut, btnGo, btnCancel, btnOpen, btnAbout;
        RadioButton rbSoA, rbZB;
        CheckBox cbAdb;
        ProgressBar pb;
        Label lblStatus;

        List<string> dropped = new List<string>();
        volatile bool cancelFlag;
        Thread worker;
        Process currentProc;
        string ffmpegPath;
        const string PkgHaeg = "com.microids.google.HaegemoniaFull";
        const string PkgZB = "com.zettabridge.launcher";

        public MainForm(string[] args)
        {
            lang = 0;   // always start in English; the language box switches to Hungarian
            Font = new Font("Segoe UI", 9f);
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(760, 640);
            MinimumSize = new Size(640, 560);
            AllowDrop = true;
            DragEnter += OnDragEnter;
            DragDrop += OnDragDrop;

            int m = 14;

            cbLang = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 110, Anchor = AnchorStyles.Top | AnchorStyles.Right };
            cbLang.Items.AddRange(new object[] { "English", "Magyar" });
            cbLang.SelectedIndex = lang;
            cbLang.Location = new Point(ClientSize.Width - cbLang.Width - m, m);
            cbLang.SelectedIndexChanged += delegate { lang = cbLang.SelectedIndex; ApplyTexts(); };
            Controls.Add(cbLang);

            lblIntro = new Label { Location = new Point(m, m), Size = new Size(ClientSize.Width - 2 * m - 130, 44), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(lblIntro);

            lblSrc = new Label { Location = new Point(m, 66), Size = new Size(ClientSize.Width - 2 * m, 32), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(lblSrc);
            tbSrc = new TextBox { Location = new Point(m, 100), Width = ClientSize.Width - 2 * m - 110, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            btnSrc = new Button { Location = new Point(ClientSize.Width - m - 100, 98), Size = new Size(100, 27), Anchor = AnchorStyles.Top | AnchorStyles.Right };
            btnSrc.Click += delegate { string p = PickFolder(tbSrc.Text); if (p != null) { tbSrc.Text = p; dropped.Clear(); } };
            Controls.Add(tbSrc); Controls.Add(btnSrc);

            lblTgt = new Label { Location = new Point(m, 136), Size = new Size(ClientSize.Width - 2 * m, 20), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(lblTgt);
            rbSoA = new RadioButton { Location = new Point(m + 8, 158), Size = new Size(ClientSize.Width - 2 * m - 8, 22), Checked = true, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            rbZB = new RadioButton { Location = new Point(m + 8, 182), Size = new Size(ClientSize.Width - 2 * m - 8, 22), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(rbSoA); Controls.Add(rbZB);

            lblOut = new Label { Location = new Point(m, 214), Size = new Size(ClientSize.Width - 2 * m, 20), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(lblOut);
            tbOut = new TextBox { Location = new Point(m, 238), Width = ClientSize.Width - 2 * m - 110, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            tbOut.Text = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Desktop), "Haegemonia-bik.mp4converter_output");
            btnOut = new Button { Location = new Point(ClientSize.Width - m - 100, 236), Size = new Size(100, 27), Anchor = AnchorStyles.Top | AnchorStyles.Right };
            btnOut.Click += delegate { string p = PickFolder(tbOut.Text); if (p != null) tbOut.Text = p; };
            Controls.Add(tbOut); Controls.Add(btnOut);

            cbAdb = new CheckBox { Location = new Point(m, 274), Size = new Size(ClientSize.Width - 2 * m, 22), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(cbAdb);
            lblAdbNote = new Label { Location = new Point(m + 20, 296), Size = new Size(ClientSize.Width - 2 * m - 20, 18), ForeColor = SystemColors.GrayText, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(lblAdbNote);

            btnGo = new Button { Location = new Point(m, 326), Size = new Size(130, 32) };
            btnGo.Click += delegate { StartConvert(); };
            btnCancel = new Button { Location = new Point(m + 140, 326), Size = new Size(100, 32), Enabled = false };
            btnCancel.Click += delegate { cancelFlag = true; try { if (currentProc != null && !currentProc.HasExited) currentProc.Kill(); } catch { } };
            btnOpen = new Button { Location = new Point(m + 250, 326), Size = new Size(190, 32), Enabled = false };
            btnOpen.Click += delegate { try { Process.Start("explorer.exe", "\"" + tbOut.Text + "\""); } catch { } };
            btnAbout = new Button { Location = new Point(ClientSize.Width - m - 130, 326), Size = new Size(130, 32), Anchor = AnchorStyles.Top | AnchorStyles.Right };
            btnAbout.Click += delegate { ShowAbout(); };
            Controls.Add(btnGo); Controls.Add(btnCancel); Controls.Add(btnOpen); Controls.Add(btnAbout);

            pb = new ProgressBar { Location = new Point(m, 370), Size = new Size(ClientSize.Width - 2 * m, 18), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(pb);
            lblStatus = new Label { Location = new Point(m, 392), Size = new Size(ClientSize.Width - 2 * m, 20), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(lblStatus);

            log = new TextBox { Location = new Point(m, 416), Size = new Size(ClientSize.Width - 2 * m, ClientSize.Height - 416 - m), Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Font = new Font("Consolas", 9f), Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right };
            Controls.Add(log);

            ApplyTexts();

            // command-line / drag-onto-exe arguments
            foreach (string a in args) if (File.Exists(a) || Directory.Exists(a)) dropped.Add(a);
            if (dropped.Count > 0) tbSrc.Text = dropped.Count == 1 ? dropped[0] : dropped[0] + "  (+" + (dropped.Count - 1) + ")";

            FormClosing += delegate { cancelFlag = true; try { if (currentProc != null && !currentProc.HasExited) currentProc.Kill(); } catch { } };
        }

        void ApplyTexts()
        {
            Text = S("title");
            lblIntro.Text = S("intro");
            lblSrc.Text = S("src");
            lblTgt.Text = S("tgt");
            rbSoA.Text = S("tgtSoA");
            rbZB.Text = S("tgtZB");
            lblOut.Text = S("out");
            btnSrc.Text = S("browse");
            btnOut.Text = S("browse");
            cbAdb.Text = S("adb");
            lblAdbNote.Text = S("adbNote");
            btnGo.Text = S("go");
            btnCancel.Text = S("cancel");
            btnOpen.Text = S("open");
            btnAbout.Text = S("about");
        }

        void ShowAbout()
        {
            MessageBox.Show(this, S("aboutbody"), S("about"), MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        string PickFolder(string start)
        {
            using (FolderBrowserDialog d = new FolderBrowserDialog())
            {
                if (!string.IsNullOrEmpty(start) && Directory.Exists(start)) d.SelectedPath = start;
                if (d.ShowDialog(this) == DialogResult.OK) return d.SelectedPath;
            }
            return null;
        }

        void OnDragEnter(object s, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop)) e.Effect = DragDropEffects.Copy;
        }

        void OnDragDrop(object s, DragEventArgs e)
        {
            string[] f = (string[])e.Data.GetData(DataFormats.FileDrop);
            if (f == null || f.Length == 0) return;
            dropped.Clear();
            dropped.AddRange(f);
            tbSrc.Text = f.Length == 1 ? f[0] : f[0] + "  (+" + (f.Length - 1) + ")";
        }

        // ---------- logging helpers (thread-safe) ----------
        void Log(string s)
        {
            if (InvokeRequired) { BeginInvoke(new Action<string>(Log), s); return; }
            log.AppendText(s + Environment.NewLine);
        }
        void SetStatus(string s, int value, int max)
        {
            if (InvokeRequired) { BeginInvoke(new Action<string, int, int>(SetStatus), s, value, max); return; }
            lblStatus.Text = s;
            pb.Maximum = Math.Max(1, max);
            pb.Value = Math.Min(Math.Max(0, value), pb.Maximum);
        }
        void SetBusy(bool busy)
        {
            if (InvokeRequired) { BeginInvoke(new Action<bool>(SetBusy), busy); return; }
            btnGo.Enabled = !busy;
            btnCancel.Enabled = busy;
            btnOpen.Enabled = !busy;
            btnSrc.Enabled = !busy; btnOut.Enabled = !busy;
            tbSrc.Enabled = !busy; tbOut.Enabled = !busy;
            rbSoA.Enabled = !busy; rbZB.Enabled = !busy; cbAdb.Enabled = !busy;
        }

        // ---------- main work ----------
        List<string> CollectBik()
        {
            List<string> list = new List<string>();
            List<string> roots = new List<string>();
            if (dropped.Count > 0) roots.AddRange(dropped);
            else if (!string.IsNullOrEmpty(tbSrc.Text.Trim())) roots.Add(tbSrc.Text.Trim());
            foreach (string r in roots)
            {
                try
                {
                    if (Directory.Exists(r))
                        list.AddRange(Directory.GetFiles(r, "*.bik", SearchOption.AllDirectories));
                    else if (File.Exists(r) && r.EndsWith(".bik", StringComparison.OrdinalIgnoreCase))
                        list.Add(r);
                }
                catch { }
            }
            list.Sort(StringComparer.OrdinalIgnoreCase);
            return list;
        }

        void StartConvert()
        {
            if (worker != null && worker.IsAlive) return;
            List<string> files = CollectBik();
            if (tbSrc.Text.Trim().Length == 0 && dropped.Count == 0) { MessageBox.Show(this, S("nosrc")); return; }
            if (files.Count == 0) { MessageBox.Show(this, S("nofiles")); return; }
            if (tbOut.Text.Trim().Length == 0) { MessageBox.Show(this, S("noout")); return; }

            bool zb = rbZB.Checked;
            string pkg = zb ? PkgZB : PkgHaeg;
            string sub = zb ? "hv" : "videos";
            string outRoot = tbOut.Text.Trim();
            string relPath = Path.Combine(Path.Combine(Path.Combine("Android", "obb"), pkg), Path.Combine(sub, "fmv"));
            string fullOut = Path.Combine(outRoot, relPath);
            bool useAdb = cbAdb.Checked;
            string devPath = "/sdcard/Android/obb/" + pkg + "/" + sub;   // adb pushes the fmv folder into this one

            log.Clear();
            cancelFlag = false;
            SetBusy(true);
            Log(string.Format(S("found"), files.Count));
            Log(string.Format(S("outis"), fullOut));

            worker = new Thread(delegate() { Run(files, fullOut, outRoot, useAdb, devPath); });
            worker.IsBackground = true;
            worker.Start();
        }

        void Run(List<string> files, string fullOut, string outRoot, bool useAdb, string devPath)
        {
            int ok = 0, skip = 0, fail = 0;
            try
            {
                ffmpegPath = PrepareFfmpeg();
                if (ffmpegPath == null) { Log(S("ffmissing")); return; }
                Directory.CreateDirectory(fullOut);

                for (int i = 0; i < files.Count; i++)
                {
                    if (cancelFlag) break;
                    string f = files[i];
                    string name = Path.GetFileName(f);
                    string outFile = Path.Combine(fullOut, Path.GetFileNameWithoutExtension(f) + ".bik.mp4");
                    if (File.Exists(outFile) && new FileInfo(outFile).Length > 0)
                    {
                        Log(string.Format(S("skip"), i + 1, files.Count, name));
                        skip++;
                        SetStatus(string.Format(S("skip"), i + 1, files.Count, name), i + 1, files.Count);
                        continue;
                    }
                    Log(string.Format(S("conv"), i + 1, files.Count, name));
                    SetStatus(string.Format(S("conv"), i + 1, files.Count, name), i, files.Count);
                    string err;
                    int code = RunProcess(ffmpegPath,
                        "-hide_banner -loglevel error -y -i " + Q(f) +
                        " -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -preset medium -crf 20" +
                        " -c:a aac -b:a 160k -ar 44100 -ac 2 -movflags +faststart " + Q(outFile), out err);
                    if (cancelFlag) { try { File.Delete(outFile); } catch { } break; }
                    if (code != 0)
                    {
                        fail++;
                        Log(string.Format(S("fail"), name));
                        if (!string.IsNullOrEmpty(err)) Log("   " + err.Trim().Replace("\n", "\n   "));
                        try { if (File.Exists(outFile)) File.Delete(outFile); } catch { }
                    }
                    else ok++;
                    SetStatus(string.Format(S("conv"), i + 1, files.Count, name), i + 1, files.Count);
                }

                if (cancelFlag) { Log(S("cancelled")); return; }
                Log(string.Format(S("done"), ok, skip, fail));

                if (useAdb && (ok + skip) > 0)
                {
                    string adb = FindAdb();
                    if (adb == null) Log(S("noadb"));
                    else
                    {
                        Log(S("adbstart"));
                        string e1, e2;
                        RunProcess(adb, "shell mkdir -p " + devPath, out e1);
                        int c = RunProcess(adb, "push " + Q(fullOut) + " " + devPath + "/", out e2);
                        if (c == 0) Log(string.Format(S("adbok"), devPath + "/fmv"));
                        else { Log(S("adbfail")); if (!string.IsNullOrEmpty(e2)) Log("   " + e2.Trim().Replace("\n", "\n   ")); }
                    }
                }
                else if ((ok + skip) > 0) Log(S("copyhint"));
            }
            catch (Exception ex) { Log("ERROR: " + ex.Message); }
            finally
            {
                currentProc = null;
                SetBusy(false);
            }
        }

        static string Q(string s) { return "\"" + s + "\""; }

        int RunProcess(string exe, string args, out string stderrText)
        {
            ProcessStartInfo psi = new ProcessStartInfo(exe, args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardError = true;
            psi.RedirectStandardOutput = true;
            psi.StandardErrorEncoding = Encoding.UTF8;
            psi.StandardOutputEncoding = Encoding.UTF8;
            StringBuilder sb = new StringBuilder();
            Process p = new Process();
            p.StartInfo = psi;
            p.ErrorDataReceived += delegate(object s, DataReceivedEventArgs e) { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
            p.OutputDataReceived += delegate(object s, DataReceivedEventArgs e) { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
            try
            {
                p.Start();
                currentProc = p;
                p.BeginErrorReadLine();
                p.BeginOutputReadLine();
                p.WaitForExit();
                stderrText = sb.ToString();
                return p.ExitCode;
            }
            catch (Exception ex)
            {
                stderrText = ex.Message;
                return -1;
            }
        }

        // ---------- bundled ffmpeg ----------
        string PrepareFfmpeg()
        {
            try
            {
                Assembly asm = Assembly.GetExecutingAssembly();
                string resName = null;
                foreach (string n in asm.GetManifestResourceNames())
                    if (n.EndsWith("ffmpeg.exe", StringComparison.OrdinalIgnoreCase)) resName = n;
                if (resName == null) return null;

                string dir = Path.Combine(Path.GetTempPath(), "Haegemonia-bik.mp4converter");
                Directory.CreateDirectory(dir);
                string target = Path.Combine(dir, "ffmpeg-7.1.1.exe");
                using (Stream rs = asm.GetManifestResourceStream(resName))
                {
                    if (!File.Exists(target) || new FileInfo(target).Length != rs.Length)
                    {
                        using (FileStream fs = new FileStream(target, FileMode.Create, FileAccess.Write))
                            rs.CopyTo(fs);
                    }
                }
                return target;
            }
            catch { return null; }
        }

        static string FindAdb()
        {
            try
            {
                string local = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "adb.exe");
                if (File.Exists(local)) return local;
                string path = Environment.GetEnvironmentVariable("PATH") ?? "";
                foreach (string d in path.Split(';'))
                {
                    if (d.Trim().Length == 0) continue;
                    string c = Path.Combine(d.Trim().Trim('"'), "adb.exe");
                    if (File.Exists(c)) return c;
                }
                string sdk = Environment.GetEnvironmentVariable("ANDROID_HOME") ?? Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
                if (!string.IsNullOrEmpty(sdk))
                {
                    string c = Path.Combine(sdk, "platform-tools", "adb.exe");
                    if (File.Exists(c)) return c;
                }
            }
            catch { }
            return null;
        }
    }
}
