const rust = import('./pkg');


rust.then(wasm => {
	function dragEnter(evt) {
		const isFile = event.dataTransfer.types.includes("Files");
		if (isFile) {
			event.preventDefault();
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
		console.log('... file.name = ' + file.name);

		var reader = new FileReader();
		reader.onload = function () {

			var arrayBuffer = this.result;
			var array = new Uint8Array(arrayBuffer);
			var result = null;
			try {
				result = wasm.decode(array);
				dropstyle(file.name, "green");
			} catch (e) {
				if (typeof e === 'string' || e instanceof String) {
					var editor = ace.edit("editor");
					editor.setValue(e);
				}
				console.log(e);
				dropstyle("failed to decode", "red");
			}
			var editor = ace.edit("editor");
			editor.setValue(result);
		}
		reader.readAsArrayBuffer(file);
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

		if (typeof annot !== 'undefined') {
			editor.session.setAnnotations([{
				row: annot.line,
				column: annot.line,
				text: annot.err,
				type: "error" // also warning and information
			}]);
		} else {
			editor.session.clearAnnotations();
		}
	}

	var area = document.getElementById('droparea');
	area.addEventListener('drop', dropHandler);
	area.addEventListener('dragenter', dragEnter);
	area.addEventListener('dragover', dragEnter);

	var editor = ace.edit("editor");
	editor.setTheme("ace/theme/monokai");
	editor.session.setMode("ace/mode/json");
	editor.session.on('change', onChange);
	editor.session.setOption("useWorker", false);
});