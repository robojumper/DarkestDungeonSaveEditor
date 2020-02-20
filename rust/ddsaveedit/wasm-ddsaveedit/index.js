const rust = import('./pkg');


rust.then(wasm => {
	wasm.greet();

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
				droperror("expected exactly one file");
				return;
			}
		} else {
			// Use DataTransfer interface to access the file(s)
			if (ev.dataTransfer.files.length === 1) {
				file = ev.dataTransfer.files[0];
			} else {
				droperror("expected exactly one file");
				return;
			}
		}
		console.log('... file.name = ' + file.name);

		var reader = new FileReader();
		reader.onload = function () {

			var arrayBuffer = this.result;
			console.log(arrayBuffer);
			var array = new Uint8Array(arrayBuffer);
			console.log(array);

			var result = wasm.decode(array);
			var editor = ace.edit("editor");
			editor.setValue(result);
		}
		reader.readAsArrayBuffer(file);
	}

	function droperror(err) {
		var dragarea = document.getElementById('droparea');
		dragarea.style.borderColor = "red";
		var errmsg = document.getElementById('errmsg');
		errmsg.textContent = err;
	}

	console.log('ok');
	var area = document.getElementById('droparea');
	area.addEventListener('drop', dropHandler);
	area.addEventListener('dragenter', dragEnter);
	area.addEventListener('dragover', dragEnter);

});