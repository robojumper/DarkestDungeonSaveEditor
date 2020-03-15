const rust = import('./pkg');
import names from './names_cache.txt';

rust.then(wasm => {

	function dragEnter(evt) {
		const isFile = event.dataTransfer.types.includes("Files");
		if (isFile) {
			event.preventDefault();
		}
	}

	function handleUpload(file) {
		var reader = new FileReader();
		reader.onload = function () {

			var arrayBuffer = this.result;
			var array = new Uint8Array(arrayBuffer);
			var result = null;
			try {
				result = wasm.decode(array);
				// FIXME: This should be throwing the exception
				// once it stops leaking stack space
				// https://github.com/rustwasm/wasm-bindgen/issues/1963
				if (result.startsWith("Error")) {
					throw result;
				}
				console.log('ok');
				dropstyle(file.name, "green");
				var editor = ace.edit("editor");
				editor.setValue(result);
			} catch (e) {
				if (typeof e === 'string' || e instanceof String) {
					var editor = ace.edit("editor");
					editor.setValue(e);
				}
				console.log(e);
				dropstyle("failed to decode", "red");
			}
		}
		reader.readAsArrayBuffer(file);
	}

	function onUpload() {
		var upload = document.getElementById('filepick');
		const curFiles = upload.files;

		if (curFiles.length == 1) {
			var file = curFiles[0];
			handleUpload(file);
		} else {
			dropstyle("expected exactly one file", "red");
			return;
		}
	}

	function dropHandler(ev) {
		// Thanks MDN
		// Prevent default behavior (Prevent file from being opened)
		ev.preventDefault();
		var file = null;
		if (ev.dataTransfer.items) {
			if (ev.dataTransfer.items.length === 1 && ev.dataTransfer.items[0].kind === 'file') {
				// Use DataTransferItemList interface to access the file
				file = ev.dataTransfer.items[0].getAsFile();
			} else {
				dropstyle("expected exactly one file", "red");
				return;
			}
		} else {
			// Use DataTransfer interface to access the file(s)
			if (ev.dataTransfer.files.length === 1) {
				file = ev.dataTransfer.files[0];
			} else {
				dropstyle("expected exactly one file", "red");
				return;
			}
		}
		console.log('file.name = ' + file.name);

		handleUpload(file);
	}

	function dropstyle(err, col) {
		var dragarea = document.getElementById('droparea');
		dragarea.style.borderColor = col;
		var errmsg = document.getElementById('errmsg');
		errmsg.textContent = err;
	}

	function onChange(ev) {
		var editor = ace.edit("editor");
		var text = editor.getValue();
		let annot = wasm.check(text);

		var markers = editor.session.getMarkers();
		for (var m in markers) {
			editor.session.removeMarker(m);
		}

		if (typeof annot !== 'undefined') {
			editor.session.setAnnotations([{
				row: annot.line,
				column: annot.col,
				text: annot.err,
				type: "error"
			}]);
			var Range = ace.Range;
			editor.session.addMarker(new Range(annot.line, annot.col, annot.eline, annot.ecol), "aceerror", annot.err);
			var download = document.getElementById('downloadlink');
			download.className = "link-disabled";
			download.removeAttribute('href');
		} else {
			editor.session.clearAnnotations();
			var download = document.getElementById('downloadlink');
			download.className = "";
			download.setAttribute('href', "");
		}
	}

	function arrayToBase64(bytes) {
		var len = bytes.byteLength;
		var binary = '';
		for (var i = 0; i < len; i++) {
			binary += String.fromCharCode(bytes[i]);
		}
		return window.btoa(binary);
	}

	function downloadFile(filename, bincontent) {
		if (window.navigator.msSaveBlob) { // // IE hack; see http://msdn.microsoft.com/en-us/library/ie/hh779016.aspx
			var blob = new Blob([bincontent], { type: 'application/octet-stream' });
			window.navigator.msSaveOrOpenBlob(blob, filename);
			return true;
		} else {
			var element = document.getElementById('downloadlink');
			element.setAttribute('href', 'data:application/octet-stream;base64,' + arrayToBase64(bincontent));
			element.setAttribute('download', filename);
			return false;
		}
	}


	function onDownload(ev) {
		var editor = ace.edit("editor");
		var text = editor.getValue();
		let result = wasm.encode(text);
		if (result === 'undefined') {
			ev.preventDefault();
		} else {
			if (downloadFile(document.getElementById('errmsg').textContent, result)) {
				ev.preventDefault();
			}
		}
	}

	wasm.init();

	var oReq = new XMLHttpRequest();
	oReq.addEventListener("load", function () {
		var text = this.responseText;
		var split_names = text.split(/\r?\n/);
		wasm.set_names(split_names);
	});
	oReq.open("GET", names);
	oReq.send();

	var area = document.getElementById('droparea');
	area.addEventListener('drop', dropHandler);
	area.addEventListener('dragenter', dragEnter);
	area.addEventListener('dragover', dragEnter);

	var download = document.getElementById('downloadlink');
	download.addEventListener('click', onDownload);

	var upload = document.getElementById('filepick');
	upload.value = "";
	upload.addEventListener('change', onUpload);

	var editor = ace.edit("editor");
	editor.setTheme("ace/theme/monokai");
	editor.session.setMode("ace/mode/json");
	editor.session.on('change', onChange);
	editor.session.setOption("useWorker", false);
	document.body.appendChild(editor.container);
});